package tv.herrin.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Real HTTPS responses, parser and private disk IO. Only the test connection trusts this certificate. */
@RunWith(AndroidJUnit4::class)
class AuthorizedCatalogIntegrationTest {
    private val json = """{"version":1,"items":[{"id":"fixture","title":"Synthetic catalog"}]}"""
    private fun response(body: String = json) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun fixture(test: (Context, MockWebServer, (String) -> HttpURLConnection) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "integration-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTLS = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTLS = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTLS.sslSocketFactory(), false); start() }
        val open: (String) -> HttpURLConnection = { url ->
            (URL(url).openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTLS.sslSocketFactory() }
        }
        try { test(context, server, open) } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun catalogValidMalformedTimeoutOfflineIsolationRedirectSizeAndFallback() = fixture { context, server, open ->
        val repo = RemoteCatalogRepository(context, open, timeoutMs = 1000)
        val url = server.url("/catalog").toString()
        fun load(endpoint: String = url) = runBlocking { repo.load(endpoint) }
        server.enqueue(response())
        val fresh = load() as CatalogLoadResult.Ready
        assertFalse(fresh.catalog.fromCache)
        assertEquals("fixture", fresh.catalog.films.single().id)
        fun cached(response: MockResponse) {
            server.enqueue(response)
            val result = load() as CatalogLoadResult.Ready
            assertTrue(result.catalog.fromCache)
            assertEquals(fresh.catalog.films, result.catalog.films)
        }
        cached(response("{broken"))
        cached(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        cached(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect-target")))
        cached(response("x".repeat(2_000_001)))
        cached(MockResponse().setHeader("Content-Type", "text/html").setBody(json))
        cached(MockResponse().setResponseCode(503))
        server.enqueue(response("invalid"))
        assertTrue(load(server.url("/different").toString()) is CatalogLoadResult.Error)
        // Actual offline socket failure uses the same snapshot; another URL never borrows it.
        server.shutdown()
        assertTrue((load() as CatalogLoadResult.Ready).catalog.fromCache)
        assertTrue(load(server.url("/uncached").toString()) is CatalogLoadResult.Error)
        android.util.Log.i("HerrinCatalog", "HTTPS: valid, malformed, timeout, redirect, oversized, HTML, 503, URL isolation and offline passed")
    }
    @Test fun posterCacheHitOfflineIsolationCorruptionRecoveryAndResponseValidation() = fixture { context, server, open ->
        val cache = AuthorizedPosterCache(context, open)
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        fun image() = MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(output.toByteArray()))
        val url = server.url("/poster.png").toString()
        server.enqueue(image())
        assertEquals(16, cache.load(url)!!.width)
        val requests = server.requestCount
        assertNotNull(cache.load(url))
        assertEquals(requests, server.requestCount)
        File(context.cacheDir, "authorized-posters").listFiles()!!.single().writeText("broken")
        server.enqueue(image())
        assertNotNull(cache.load(url))
        assertEquals(requests + 1, server.requestCount)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        assertNull(cache.load(server.url("/html").toString()))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url))
        assertNull(cache.load(server.url("/redirect").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("x".repeat(4_000_001)))
        assertNull(cache.load(server.url("/oversized").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("not an image"))
        assertNull(cache.load(server.url("/invalid").toString()))
        server.shutdown()
        assertNotNull(cache.load(url))
        assertNull(AuthorizedUrls.https("http://localhost/poster"))
    }
}
