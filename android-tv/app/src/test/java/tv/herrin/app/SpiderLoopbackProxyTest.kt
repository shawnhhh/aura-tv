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

    @Test fun forwardsBoundedMedia3RangeHeaderToPluginAndReturns206WithoutCredentials() {
        val bytes = "streaming".toByteArray(StandardCharsets.UTF_8)
        var captured: Map<String, String>? = null
        SpiderLoopbackProxy("range-site") { params ->
            captured = params
            arrayOf(206, "video/mp4", ByteArrayInputStream(bytes.copyOfRange(2, 6)),
                mapOf("Content-Range" to "bytes 2-5/9", "Accept-Ranges" to "bytes"))
        }.use { proxy ->
            val conn = request(proxy.url + "&range=bytes%3D0-1")
            conn.setRequestProperty("Range", "bytes=2-5")
            conn.setRequestProperty("Authorization", "Bearer PRIVATE_CREDENTIAL")
            assertEquals(206, conn.responseCode)
            assertEquals("bytes 2-5/9", conn.getHeaderField("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(2, 6), conn.inputStream.use { it.readBytes() })
            assertEquals("bytes=2-5", captured?.get("range"))
            assertFalse(captured.orEmpty().containsKey("authorization"))
            assertFalse(captured.orEmpty().values.any { it.contains("PRIVATE_CREDENTIAL") })
            assertFalse(captured.orEmpty().containsKey("session"))
            conn.disconnect()
        }
    }

    @Test fun rejectsMalformedRangeBeforePluginCallback() {
        val count = AtomicInteger()
        SpiderLoopbackProxy("range-site") {
            count.incrementAndGet()
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            val conn = request(proxy.url)
            conn.setRequestProperty("Range", "bytes=0-99,100-200")
            assertEquals(400, conn.responseCode)
            assertEquals(0, count.get())
            conn.disconnect()
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
            // Java HttpURLConnection can silently filter Origin. Send actual
            // HTTP header bytes so the guard itself is tested reliably.
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 2_000
                val target = proxy.url.substringAfter("127.0.0.1:" + proxy.port)
                val wire = "GET " + target + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + proxy.port + "\r\n" +
                    "Origin: https://attacker.test\r\n\r\n"
                socket.getOutputStream().write(wire.toByteArray(StandardCharsets.US_ASCII))
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                assertTrue(statusLine.startsWith("HTTP/1.1 400"))
            }
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
