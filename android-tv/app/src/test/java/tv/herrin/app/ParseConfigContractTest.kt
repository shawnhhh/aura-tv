/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import tv.herrin.app.fongmi.parse.Parse
import tv.herrin.app.fongmi.parse.ParseCallback
import tv.herrin.app.fongmi.parse.ParseCatalog
import tv.herrin.app.fongmi.parse.ParseJob
import tv.herrin.app.fongmi.parse.Result
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fixed VodConfig -> production parser catalog -> actual ParseJob contracts. */
class ParseConfigContractTest {
    private fun catalog(parses: String): ParseCatalog {
        val config = TvBoxConfigParser.parse("""{"sites":[],"parses":$parses}""",
            "https://warehouse.example/config") as TvBoxConfig.Single
        return ParseCatalog(config.playbackParsers.map(FongMiParsePlayback::configuration))
    }

    @Test fun emptyConfigDoesNotActivateAnAutomaticParser() {
        val catalog = catalog("[]")
        assertTrue(catalog.parses.isEmpty())
        assertTrue(catalog.parse.isEmpty)
        assertTrue(catalog.getParse("unknown").isEmpty)
    }

    @Test fun nonemptyConfigDefaultsToSupervisorAndPreservesNamedAndFlagSelection() {
        val catalog = catalog("""[
            {"name":"first","type":1,"url":"https://first.example/?url=",
             "ext":{"flag":["vip"],"header":{"User-Agent":"first-agent"}}},
            {"name":"second","type":1,"url":"https://second.example/?url="},
            {"name":"web","type":0,"url":"https://web.example/?url="}
        ]""")
        assertEquals(4, catalog.parse.type)
        assertEquals("Super", catalog.parse.name)
        assertEquals(listOf(4, 1, 1, 0), catalog.parses.map { it.type })
        assertSame(catalog.parses[1], catalog.getParse("first"))
        assertEquals("first-agent", catalog.getParse("first").header["User-Agent"])
        assertEquals(listOf("first"), catalog.getParses(1, "vip").map { it.name })
        assertEquals(listOf("first", "second"), catalog.getParses(1, "other").map { it.name })
        assertEquals(listOf("web"), catalog.getParses(0, "vip").map { it.name })
        assertTrue(catalog.getParse("unknown").isEmpty)
    }

    @Test fun defaultSupervisorTriesWorkingHttpParserWhenFirstConfiguredParserFails() {
        MockWebServer().use { broken -> MockWebServer().use { working ->
            val brokenSeen = CountDownLatch(1)
            broken.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    brokenSeen.countDown()
                    return MockResponse().setResponseCode(500).setBody("{}")
                }
            }
            working.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(brokenSeen.await(2, TimeUnit.SECONDS)) { "first candidate must be requested" }
                    return MockResponse().setBody("""{"url":"https://media.example/original.mp4"}""")
                }
            }
            val catalog = catalog("""[
                {"name":"broken","type":1,"url":"${broken.url("/parse?url=")}"},
                {"name":"working","type":1,"url":"${working.url("/parse?url=")}",
                 "ext":{"header":{"User-Agent":"working-agent"}}},
                {"name":"never-auto-run","type":2,"url":"FixtureOnly"}
            ]""")
            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
            val completed = CountDownLatch(1)
            val output = AtomicReference<String>()
            val errors = AtomicInteger()
            val host = object : ParseJob.Host {
                override fun getParse(): Parse = catalog.parse
                override fun getParse(name: String): Parse = catalog.getParse(name)
                override fun getParses(): List<Parse> = catalog.parses
                override fun getParses(type: Int, flag: String): List<Parse> = catalog.getParses(type, flag)
                override fun getSiteClick(key: String) = ""
                override fun scheduler() = scheduler
                override fun timeoutMillis() = 3_000L
                override fun webSupported() = false
                override fun post(task: Runnable) = task.run()
                override fun newCall(url: String, headers: Map<String, String>): Call =
                    client.newCall(Request.Builder().url(url).apply {
                        headers.forEach { (name, value) -> header(name, value) }
                    }.build())
                override fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): Any =
                    throw AssertionError("default supervisor must not run JAR parsers")
                override fun jsonExtMix(flag: String, key: String, name: String,
                    jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): Any =
                    throw AssertionError("default supervisor must not run JAR parsers")
                override fun startWeb(key: String, from: String, headers: Map<String, String>,
                    url: String, click: String, callback: ParseCallback, detect: Boolean): AutoCloseable =
                    throw AssertionError("no WebView parser configured")
            }
            val job = ParseJob.create(host, object : ParseCallback {
                override fun onParseSuccess(headers: Map<String, String>, url: String, from: String) {
                    output.set(url); completed.countDown()
                }
                override fun onParseError() { errors.incrementAndGet(); completed.countDown() }
            })
            try {
                job.start(Result.fromObject("""{"url":"https://watch.example/id","flag":"vip"}"""), true)
                assertTrue("parser completion deadline", completed.await(5, TimeUnit.SECONDS))
                assertEquals("https://media.example/original.mp4", output.get())
                assertEquals(0, errors.get())
                assertTrue(brokenSeen.await(1, TimeUnit.SECONDS))
                assertEquals("working-agent", working.takeRequest(1, TimeUnit.SECONDS)?.getHeader("User-Agent"))
            } finally {
                job.stop(); scheduler.shutdownNow()
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        } }
    }
}
