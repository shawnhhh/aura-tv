package tv.herrin.app

import com.github.catvod.Proxy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class SpiderLoopbackProxyTest {
    private fun request(url: String, origin: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 3_000
            instanceFollowRedirects = false
            origin?.let { setRequestProperty("Origin", it) }
        }

    @Test fun streamsAuthorizedJavaInputStreamWithRealRandomPort() {
        val calls = AtomicInteger()
        val media = "the original authorized test media".toByteArray(StandardCharsets.UTF_8)
        SpiderLoopbackProxy("site-demo") { args ->
            calls.incrementAndGet()
            assertEquals("jar", args["do"])
            assertEquals("site-demo", args["siteKey"])
            assertEquals("ep1", args["part"])
            assertFalse(args.containsKey("session"))
            arrayOf(200, "video/mp2t", ByteArrayInputStream(media),
                mapOf("Cache-Control" to "no-store", "X-Secret" to "blocked"))
        }.use { proxy ->
            assertTrue(proxy.port in 1..65535)
            assertTrue(proxy.url.startsWith("http://127.0.0.1:"))
            val response = request(proxy.url + "&part=ep1")
            assertEquals(200, response.responseCode)
            assertEquals("video/mp2t", response.contentType)
            assertArrayEquals(media, response.inputStream.use { it.readBytes() })
            assertNull(response.getHeaderField("X-Secret"))
            assertEquals(1, calls.get())
            response.disconnect()
        }
    }

    @Test fun rejectsForgedTokenSiteOriginAndMethod() {
        val count = AtomicInteger()
        SpiderLoopbackProxy("private-site") {
            count.incrementAndGet()
            arrayOf(200, "text/plain", ByteArrayInputStream("ok".toByteArray()))
        }.use { proxy ->
            val path = proxy.url.substringBefore("?")
            assertEquals(403, request(path + "?do=jar&siteKey=private-site").responseCode)
            assertEquals(403, request(proxy.url.replace("private-site", "wrong-site")).responseCode)
            assertEquals(403, request(proxy.url.replace("do=jar", "do=anything")).responseCode)
            assertEquals(400, request(proxy.url, origin = "https://attacker.test").responseCode)
            val post = request(proxy.url)
            post.requestMethod = "POST"
            post.doOutput = true
            assertEquals(405, post.responseCode)
            assertEquals(0, count.get())
        }
    }

    @Test fun rejectsUntrustedHostBeforePluginCallback() {
        val count = AtomicInteger()
        SpiderLoopbackProxy("site") {
            count.incrementAndGet()
            arrayOf(200, "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 2_000
                val message = "GET /proxy?do=jar HTTP/1.1\r\nHost: evil.test\r\n\r\n"
                socket.getOutputStream().write(message.toByteArray(StandardCharsets.US_ASCII))
                val answer = socket.getInputStream().bufferedReader().readLine()
                assertTrue(answer.startsWith("HTTP/1.1 400"))
            }
            assertEquals(0, count.get())
        }
    }

    @Test fun malformedJavaProxyResponsesProduceOnlyGenericErrors() {
        SpiderLoopbackProxy("site") { arrayOf(200, "video/mp4", "not-an-inputstream") }.use {
            val response = request(it.url)
            assertEquals(502, response.responseCode)
            assertFalse(response.errorStream.bufferedReader().readText().contains("not-an-inputstream"))
        }
        SpiderLoopbackProxy("site") { arrayOf(302, "text/plain", ByteArrayInputStream(byteArrayOf())) }.use {
            assertEquals(502, request(it.url).responseCode)
        }
    }

    @Test fun sessionUrlIsUsableAndCanBeInvalidated() {
        SpiderLoopbackProxy("site") {
            arrayOf(200, "text/plain", ByteArrayInputStream(byteArrayOf(7)))
        }.use { proxy ->
            Proxy.set(proxy.port, proxy.token)
            val local = Proxy.getUrl(true)
            assertTrue(local.contains("127.0.0.1:" + proxy.port))
            assertTrue(local.contains("session="))
            assertEquals(200, request(local + "&do=jar&siteKey=site").responseCode)
        }
        Proxy.set(-1, "")
        assertEquals(-1, Proxy.getPort())
        assertFalse(Proxy.getUrl(true).contains("session="))
    }
}
