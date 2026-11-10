package tv.herrin.app

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Original HTTP protocol fixtures only; never real provider/playback evidence. */
class SpiderManifestReferenceTest {
    private fun client() = OkHttpClient.Builder()
        .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(2, TimeUnit.SECONDS).build()

    @Test fun resolvesRealResponseBytesAndRelativeManifestReferencesWithoutInjectedRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n"))
            server.start()
            val events = mutableListOf<String>()
            SpiderManifestReference(events::add, client()).use { resolver ->
                val text = resolver.load("http://provider.test:${server.port}/hls/index.m3u8", mapOf("User-Agent" to "OriginalFixture"))
                assertTrue(text.contains("http://provider.test:${server.port}/hls/segment.ts"))
                val request = server.takeRequest()
                assertNull(request.getHeader("Range"))
                assertNull(request.getHeader("Cookie"))
                assertEquals(listOf("HLS_REFERENCE_HTTP_200"), events)
            }
        }
    }

    @Test fun foreignRedirectAndCredentialsAreRejectedWithoutFetchingForeignOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://foreign.test/private"))
            server.start()
            SpiderManifestReference({}, client()).use { resolver ->
                try { resolver.load("http://provider.test:${server.port}/index.m3u8", emptyMap()); fail() }
                catch (_: IllegalArgumentException) {}
                assertEquals(1, server.requestCount)
                try { resolver.load("http://provider.test:${server.port}/index.m3u8", mapOf("Cookie" to "private")); fail() }
                catch (_: java.io.IOException) {}
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun nonManifestAndPrivateAddressesCannotBecomeAuthorizedHls() {
        SpiderManifestReference({}, client()).use { resolver ->
            try { resolver.load("http://127.0.0.1/private", emptyMap()); fail() }
            catch (_: IllegalArgumentException) {}
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>private login</html>"))
            server.start()
            val codes = mutableListOf<String>()
            SpiderManifestReference(codes::add, client()).use { resolver ->
                try { resolver.load("http://provider.test:${server.port}/index.m3u8", emptyMap()); fail() }
                catch (_: java.io.IOException) {}
            }
            assertEquals(listOf("HLS_REFERENCE_HTTP_200", "HLS_BODY_HTML"), codes)
        }
    }
}
