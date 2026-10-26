package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class AuthorizedLivePlaylistTest {
    @Test fun onlyExplicitHttpsNamedChannelListsCanBeLoaded() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start()
        }
        val open: (String) -> HttpURLConnection = { url ->
            (URL(url).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientTls.sslSocketFactory()
            }
        }
        val repo = RemoteLivePlaylistRepository(open, timeoutMs = 1000)
        val url = server.url("/channels.m3u").toString()
        val valid = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Test News\nhttps://licensed.example.org/live.m3u8"
        fun load() = runBlocking { repo.load(url) }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody(valid))
            val first = load() as LivePlaylistResult.Ready
            assertEquals("Test News", first.channels.single().name)
            assertEquals("News", first.channels.single().group)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url))
            assertTrue(load() is LivePlaylistResult.Error)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(valid))
            assertTrue(load() is LivePlaylistResult.Error)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl").setBody(
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttps://example.org/fragment.ts"
            ))
            assertTrue(load() is LivePlaylistResult.Error)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl")
                .setBody("x".repeat(512_001)))
            assertTrue(load() is LivePlaylistResult.Error)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertTrue(load() is LivePlaylistResult.Error)
            assertTrue(runBlocking { repo.load("http://insecure.example.org/channels") } is LivePlaylistResult.Error)
        } finally {
            server.shutdown()
        }
    }
}
