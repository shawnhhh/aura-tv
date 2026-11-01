package tv.herrin.app

import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import tv.herrin.app.fongmi.parse.Parse
import tv.herrin.app.fongmi.parse.JarParser
import tv.herrin.app.fongmi.parse.MediaSniffer
import tv.herrin.app.fongmi.parse.ParseCallback
import tv.herrin.app.fongmi.parse.ParseJob
import tv.herrin.app.fongmi.parse.Result
import java.io.File
import java.net.URLClassLoader
import java.util.LinkedHashMap
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Direct port tests run real HTTP and reflection from an actual local JAR. */
class M467ParseJobTest {
    private class Completion : ParseCallback {
        val latch = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        var url: String? = null
        var headers: Map<String,String> = emptyMap()
        override fun onParseSuccess(headers: Map<String,String>, url: String, from: String) {
            this.headers = headers; this.url = url; successes.incrementAndGet(); latch.countDown()
        }
        override fun onParseError() { failures.incrementAndGet(); latch.countDown() }
        fun await() { assertTrue("parse callback deadline", latch.await(4, TimeUnit.SECONDS)) }
    }
    private open class Host(val selected: Parse, val parsers: List<Parse> = listOf(selected)) : ParseJob.Host, AutoCloseable {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val canceled = AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.incrementAndGet() }
        }).build()
        var webHeaders: Map<String,String>? = null
        var webClick: String? = null
        var timeout = 2_000L
        var jar: ClassLoader? = null
        override fun getParse(): Parse = selected
        override fun getParse(name: String): Parse = parsers.first { it.name == name }
        override fun getParses(): List<Parse> = parsers
        override fun getParses(type: Int, flag: String): List<Parse> {
            val values = parsers.filter { it.type == type }
            return values.filter { it.ext.flag.contains(flag) }.ifEmpty { values }
        }
        override fun getSiteClick(key: String) = "site.click()"
        override fun scheduler() = timer
        override fun timeoutMillis() = timeout
        override fun post(task: Runnable) = task.run()
        override fun webSupported() = true
        override fun newCall(url: String, headers: Map<String,String>): Call = client.newCall(
            Request.Builder().url(url).apply { headers.forEach { (k,v) -> header(k,v) } }.build())
        override fun startWeb(key: String, from: String, headers: Map<String,String>, url: String,
            click: String, callback: ParseCallback, detect: Boolean): AutoCloseable {
            webHeaders = headers; webClick = click
            callback.onParseSuccess(headers, "https://fixture.test/browser-output", from)
            return AutoCloseable { }
        }
        override fun jsonExt(key: String, jxs: LinkedHashMap<String,String>, url: String): Any =
            JarParser.invoke(jar, 2, key, "", "", jxs, null, url)
        override fun jsonExtMix(flag: String, key: String, name: String,
            jxs: LinkedHashMap<String,HashMap<String,String>>, url: String): Any =
            JarParser.invoke(jar, 3, key, name, flag, null, jxs, url)
        override fun close() { timer.shutdownNow(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    private fun parse(type: Int, url: String, name: String = "json") = Parse.get(type,url).apply { this.name = name }
    private fun result(extra: String = "") = Result.fromObject("""{"url":"https://watch.test/id","flag":"vip"$extra}""")
    private fun fixtureJar(): URLClassLoader {
        val output = File.createTempFile("m467-parser-", ".jar").apply { deleteOnExit() }
        JarOutputStream(output.outputStream()).use { jar ->
            listOf("JsonM467Fixture", "MixM467Fixture").forEach { name ->
                val entry = "com/github/catvod/parser/$name.class"
                jar.putNextEntry(JarEntry(entry))
                javaClass.classLoader!!.getResourceAsStream(entry)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return URLClassLoader(arrayOf(output.toURI().toURL()), ClassLoader.getSystemClassLoader().parent)
    }
    @Test fun type0UsesSiteClickAndParserHeaderPriority() {
        val parser = parse(0,"https://parse.test/?url=").apply {
            click = "parser.click()"; ext.setHeader(mapOf("User-Agent" to "parser-agent"))
        }
        Host(parser).use { host ->
            val completed = Completion()
            ParseJob.create(host,completed).start(result(",\"header\":{\"Referer\":\"https://watch.test/\"}"),true)
            completed.await()
            assertEquals("site.click()",host.webClick)
            assertEquals(mapOf("User-Agent" to "parser-agent"),host.webHeaders)
            assertEquals(1,completed.successes.get())
        }
    }
    @Test fun explicitJsonDirectiveOverridesSelectedBrowserAndKeepsResultHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/explicit"}"""))
            Host(parse(0,"https://unused.test/?url=")).use { host ->
                val completed = Completion()
                val directive = ",\"playUrl\":\"json:${server.url("/?url=")}\",\"header\":{\"User-Agent\":\"result-agent\"}"
                ParseJob.create(host,completed).start(result(directive),true); completed.await()
                assertEquals("https://cdn.test/explicit",completed.url)
                assertEquals("result-agent",server.takeRequest().getHeader("User-Agent"))
                assertNull(host.webHeaders)
            }
        }
    }
    @Test fun namedDirectiveOverridesSelectedParser() {
        val selected = parse(0,"https://selected.test/?url=","selected")
        val named = parse(0,"https://named.test/?url=","named").apply { ext.setHeader(mapOf("User-Agent" to "named-agent")) }
        Host(selected,listOf(selected,named)).use { host ->
            val completed = Completion()
            ParseJob.create(host,completed).start(result(",\"playUrl\":\"parse:named\""),true); completed.await()
            assertEquals("named-agent",host.webHeaders?.get("User-Agent"))
        }
    }
    @Test fun type1RealHttpUsesDataUrlAndResponseHeaderReplacement() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"url":"https://cdn.test/opaque"},"ua":"response-agent"}"""))
            Host(parse(1,server.url("/json?url=").toString()).apply {
                ext.setHeader(mapOf("Referer" to "https://parser.test/"))
            }).use { host ->
                val completed = Completion(); ParseJob.create(host,completed).start(result(),true); completed.await()
                assertEquals("https://cdn.test/opaque",completed.url)
                assertEquals(mapOf("User-Agent" to "response-agent"),completed.headers)
                assertEquals("https://parser.test/",server.takeRequest().getHeader("Referer"))
            }
        }
    }
    @Test fun type2LoadsActualJarAndRetainsNeedParseWebContinuation() {
        fixtureJar().use { jar ->
            val json = parse(1,"https://json.test/?url=").apply { ext.setFlag(listOf("vip")) }
            val ext = parse(2,"M467Fixture","extended").apply { ext.setHeader(mapOf("User-Agent" to "fallback")) }
            Host(ext,listOf(json,ext)).use { host ->
                host.jar = jar; val completed = Completion()
                ParseJob.create(host,completed).start(result(),true); completed.await()
                assertEquals("https://fixture.test/browser-output",completed.url)
                assertEquals(mapOf("User-Agent" to "fallback"),host.webHeaders)
            }
        }
    }
    @Test fun type3LoadsActualJarAndPreservesMixedAbiArgumentOrder() {
        fixtureJar().use { jar ->
            val mixed = parse(3,"M467Fixture","mixed")
            Host(mixed,listOf(parse(1,"https://json.test/?url="),mixed)).use { host ->
                host.jar = jar; val completed = Completion()
                ParseJob.create(host,completed).start(result(),true); completed.await()
                assertEquals("https://fixture.test/stream",completed.url)
                assertEquals(0,completed.failures.get())
            }
        }
    }
    @Test fun superParseRacesActualHttpAndCancelsSlowLoser() {
        MockWebServer().use { slow -> MockWebServer().use { fast ->
            slow.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/slow"}""").setBodyDelay(2,TimeUnit.SECONDS))
            fast.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/fast"}"""))
            val aggregate = parse(4,"","all")
            Host(aggregate,listOf(parse(1,slow.url("/?url=").toString(),"slow"),
                parse(1,fast.url("/?url=").toString(),"fast"))).use { host ->
                val completed = Completion(); val start = System.nanoTime()
                ParseJob.create(host,completed).start(result(),true); completed.await()
                assertEquals("https://cdn.test/fast",completed.url)
                assertTrue("must not wait for slow parser",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1500)
                assertEquals(1,completed.successes.get()); assertEquals(0,completed.failures.get())
            }
        } }
    }
    @Test fun timeoutCancelsHttpAndDeliversOneFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/late"}""").setBodyDelay(2,TimeUnit.SECONDS))
            Host(parse(1,server.url("/?url=").toString())).use { host ->
                host.timeout = 100; val completed = Completion()
                ParseJob.create(host,completed).start(result(),true); completed.await()
                assertEquals(1,completed.failures.get()); assertEquals(0,completed.successes.get())
                assertTrue("in-flight request must be canceled",host.canceled.get() > 0)
            }
        }
    }
    @Test fun stoppedJobNeverCallsClientAndRejectsLateCompletion() {
        Host(parse(0,"https://web.test/?url=")).use { host ->
            val completed = Completion(); val job = ParseJob.create(host,completed)
            job.stop(); job.onParseSuccess(emptyMap(),"https://cdn.test/late",""); job.onParseError()
            assertEquals(0,completed.successes.get()); assertEquals(0,completed.failures.get())
        }
    }
    @Test fun sniffingRequiresHttpMimeOrBytesAndNeverMp4SuffixAlone() {
        assertFalse(MediaSniffer.isMedia("text/html", "<html>not media</html>".toByteArray()))
        assertTrue(MediaSniffer.isMedia("application/octet-stream", "#EXTM3U\n".toByteArray()))
        assertTrue(MediaSniffer.isMedia("video/mp4", byteArrayOf()))
        assertFalse(MediaSniffer.isMedia("application/json", "{\"url\":\"clip.mp4\"}".toByteArray()))
    }
}
