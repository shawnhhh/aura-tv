package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class M476SpiderHlsRewriteTest {
    @Test fun keepsRemoteUrlsAndRewritesOldProxyMediaAndKeyUrisToCurrentSession() {
        SpiderLoopbackProxy("approved") {
            arrayOf(200, "video/MP2T", ByteArrayInputStream(byteArrayOf(0x47)))
        }.use { proxy ->
            val playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-KEY:METHOD=AES-128,URI="proxy://do=jar&siteKey=approved&id=secret-key"
                #EXTINF:4,
                http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&id=chunk-1
                #EXTINF:4,
                https://authorized-cdn.example/segment2.ts
            """.trimIndent() + "\n"
            val result = HerrinSpiderHlsProxyRewrite.rewrite(
                playlist, "approved", proxy) ?: error("Valid playlist denied")
            assertTrue(result.startsWith("#EXTM3U"))
            assertFalse(result.contains("127.0.0.1:9978"))
            assertFalse(result.contains("proxy://"))
            assertTrue(result.contains("127.0.0.1:${proxy.port}"))
            assertTrue(result.contains("session=${proxy.token}"))
            assertTrue(result.contains("https://authorized-cdn.example/segment2.ts"))
            assertTrue(result.contains("URI=\"http://127.0.0.1:"))
        }
    }

    @Test fun wrongSiteOrUnapprovedLocalHostPathCannotEscapeTheManifest() {
        SpiderLoopbackProxy("approved") {
            arrayOf(200, "video/mp2t", ByteArrayInputStream(byteArrayOf(0x47)))
        }.use { proxy ->
            assertNull(HerrinSpiderHlsProxyRewrite.rewrite(
                "#EXTM3U\nhttp://127.0.0.1:9978/proxy?do=jar&siteKey=other&id=1",
                "approved", proxy))
            assertNull(HerrinSpiderHlsProxyRewrite.rewrite(
                "#EXTM3U\nhttp://127.0.0.1:9978/admin?do=jar",
                "approved", proxy))
            assertNull(HerrinSpiderHlsProxyRewrite.rewrite(
                "#EXTM3U\n#EXT-X-KEY:URI=\"proxy://do=py&siteKey=approved\"",
                "approved", proxy))
            assertNull(HerrinSpiderHlsProxyRewrite.rewrite(
                "<html>not a real playlist</html>", "approved", proxy))
        }
    }

    @Test fun proxyServesActualAuthenticatedRewrittenHlsBodyNotJustAString() {
        lateinit var uri: String
        SpiderLoopbackProxy("approved", staticHandler = { params ->
            assertEquals("jar", params["do"])
            val nested = "#EXTM3U\n#EXTINF:5,\n" +
                "proxy://do=jar&siteKey=approved&chunk=1\n"
            arrayOf(200, "application/vnd.apple.mpegurl",
                ByteArrayInputStream(nested.toByteArray(Charsets.UTF_8)))
        }) { params ->
            arrayOf(200, "video/mp2t", ByteArrayInputStream(byteArrayOf(0x47)))
        }.use { proxy ->
            uri = SpiderProxyPlayback.rebaseUrl("proxy://", "approved", proxy)
                ?: error("No approved static route")
            val conn = URL(uri).openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            try {
                assertEquals(200, conn.responseCode)
                val manifest = conn.inputStream.bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("#EXTM3U"))
                assertFalse(manifest.contains("proxy://"))
                assertTrue(manifest.contains("127.0.0.1:${proxy.port}"))
                assertTrue(manifest.contains("session=${proxy.token}"))
            } finally { conn.disconnect() }
        }
    }
}
