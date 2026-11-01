package tv.herrin.app

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class M467SpiderProxyTransportTest {
    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false; connectTimeout = 3000; readTimeout = 10000
    }

    @Test fun upstreamResponseFactoryKeepsObjectIdentityAndMarksOriginErrors() {
        val original = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,
            "text/plain", "authorization denied")
        assertSame(original, FongMiProxyResponse.createResponse(arrayOf(original, "unused")))
        assertEquals("1", original.getHeader("X-TV-Proxy-Error"))
        val custom = FongMiProxyResponse.createResponse(arrayOf(299, "video/mp4",
            ByteArrayInputStream(byteArrayOf(9)), mapOf("ETag" to "version", "Content-Length" to "100")))
        assertEquals(299, custom.status.requestStatus)
        assertEquals("version", custom.getHeader("ETag"))
        assertNull(custom.getHeader("Content-Length"))
        original.close(); custom.close()
    }

    @Test fun preservesOriginHttpErrorsAndSafeRedirectWithoutOpeningNetworkProxy() {
        val calls = AtomicInteger()
        SpiderLoopbackProxy("status-site") { params ->
            calls.incrementAndGet()
            when (params["part"]) {
                "redirect" -> arrayOf(307, "text/plain", ByteArrayInputStream(byteArrayOf()),
                    mapOf("Location" to "https://media.example.org/authorized/segment.ts"))
                "range" -> arrayOf(416, "text/plain", ByteArrayInputStream(byteArrayOf()),
                    mapOf("Content-Range" to "bytes */10737418240"))
                "unchanged" -> arrayOf(304, "video/mp4", ByteArrayInputStream(byteArrayOf()),
                    mapOf("ETag" to "same-content"))
                else -> arrayOf(403, "text/plain", ByteArrayInputStream("denied".toByteArray()))
            }
        }.use { proxy ->
            connection(proxy.url + "&part=redirect").let {
                assertEquals(307, it.responseCode)
                assertEquals("https://media.example.org/authorized/segment.ts", it.getHeaderField("Location"))
                it.disconnect()
            }
            connection(proxy.url + "&part=range").let {
                assertEquals(416, it.responseCode)
                assertEquals("bytes */10737418240", it.getHeaderField("Content-Range"))
                assertEquals("1", it.getHeaderField("X-TV-Proxy-Error")); it.disconnect()
            }
            connection(proxy.url).let {
                assertEquals(403, it.responseCode)
                assertEquals("denied", it.errorStream.bufferedReader().readText()); it.disconnect()
            }
            connection(proxy.url + "&part=unchanged").let {
                assertEquals(304, it.responseCode)
                assertEquals("same-content", it.getHeaderField("ETag")); it.disconnect()
            }
            assertEquals(4, calls.get())
        }
    }

    @Test fun rangeSeekAcross2GiB4GiB8GiBOf10GiBVirtualMovieReturnsRealBytes() {
        val size = 10L * 1024 * 1024 * 1024
        SpiderLoopbackProxy("10gb-movie") { params ->
            val range = params.getValue("range").removePrefix("bytes=").split('-')
            val start = range[0].toLong(); val end = range[1].toLong()
            val bytes = ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }
            arrayOf(206, "video/x-matroska", ByteArrayInputStream(bytes),
                mapOf("Content-Range" to "bytes $start-$end/$size", "Accept-Ranges" to "bytes"))
        }.use { proxy ->
            repeat(3) {
                for (offset in listOf(2L, 8L, 4L, 0L, 8L)) {
                    val start = offset * 1024 * 1024 * 1024 + 123
                    val end = start + 65535
                    val conn = connection(proxy.url).apply { setRequestProperty("Range", "bytes=$start-$end") }
                    assertEquals(206, conn.responseCode)
                    assertEquals("bytes $start-$end/$size", conn.getHeaderField("Content-Range"))
                    assertArrayEquals(ByteArray(65536) { ((start + it) % 251).toByte() }, conn.inputStream.use { it.readBytes() })
                    conn.disconnect()
                }
            }
        }
    }

    @Test fun continuouslyStreamsBeyondFormer512MiBLimitWithBoundedReadBuffer() {
        val expected = 513L * 1024 * 1024 + 37
        val released = AtomicBoolean()
        SpiderLoopbackProxy("long-stream") {
            val source = object : InputStream() {
                var remaining = expected
                override fun read(): Int = if (remaining-- > 0) 0x5a else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(length.toLong(), remaining).toInt()
                    bytes.fill(0x5a, offset, offset + count); remaining -= count; return count
                }
                override fun close() { released.set(true) }
            }
            arrayOf(200, "video/x-matroska", source)
        }.use { proxy ->
            val conn = connection(proxy.url)
            assertEquals(200, conn.responseCode)
            var received = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    assertEquals(0x5a.toByte(), buffer[0]); assertEquals(0x5a.toByte(), buffer[count - 1])
                    received += count
                }
            }
            assertEquals(expected, received); assertTrue(released.get()); conn.disconnect()
        }
    }

    @Test fun fourHlsSegmentsRunConcurrentlyAndCloseReleasesBlockedInputStream() {
        val entered = CountDownLatch(4)
        val executor = Executors.newFixedThreadPool(4)
        try {
            SpiderLoopbackProxy("hls") { params ->
                entered.countDown()
                check(entered.await(5, TimeUnit.SECONDS))
                arrayOf(200, "video/mp2t", ByteArrayInputStream(params.getValue("part").toByteArray()))
            }.use { proxy ->
                val results = (1..4).map { part -> executor.submit<String> {
                    val conn = connection(proxy.url + "&part=$part")
                    try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                } }
                assertEquals(listOf("1", "2", "3", "4"), results.map { it.get(8, TimeUnit.SECONDS) })
            }
        } finally { executor.shutdownNow() }
        val reading = CountDownLatch(1); val closed = CountDownLatch(1)
        val source = object : InputStream() {
            override fun read(): Int { reading.countDown(); closed.await(5, TimeUnit.SECONDS); return -1 }
            override fun close() { closed.countDown() }
        }
        val proxy = SpiderLoopbackProxy("release") { arrayOf(200, "video/mp4", source) }
        val conn = connection(proxy.url)
        val consumer = Executors.newSingleThreadExecutor()
        try {
            val result = consumer.submit { runCatching { conn.inputStream.use { it.read() } } }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            proxy.close()
            assertTrue(closed.await(1, TimeUnit.SECONDS)); result.get(3, TimeUnit.SECONDS)
        } finally { proxy.close(); conn.disconnect(); consumer.shutdownNow() }
    }

    @Test fun forwardsConditionalAndMediaHeadersWithoutSessionOrCredentials() {
        SpiderLoopbackProxy("headers", staticHandler = { params ->
            assertFalse(params.containsKey("siteKey")); assertFalse(params.containsKey("session"))
            assertFalse(params.containsKey("authorization")); assertFalse(params.containsKey("cookie"))
            assertEquals("stable", params["if-range"])
            assertEquals("video/*", params["accept"])
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(4)),
                mapOf("Content-Encoding" to "identity", "Set-Cookie" to "private", "Authorization" to "private"))
        }) { error("Wrong instance dispatch") }.use { proxy ->
            val conn = connection(proxy.url + "&__herrin_static=1").apply {
                setRequestProperty("If-Range", "stable"); setRequestProperty("Accept", "video/*")
                setRequestProperty("Authorization", "Bearer private"); setRequestProperty("Cookie", "private")
            }
            assertEquals(200, conn.responseCode); assertNull(conn.getHeaderField("Set-Cookie"))
            assertNull(conn.getHeaderField("Authorization")); assertEquals("identity", conn.getHeaderField("Content-Encoding"))
            assertArrayEquals(byteArrayOf(4), conn.inputStream.use { it.readBytes() }); conn.disconnect()
        }
    }
}
