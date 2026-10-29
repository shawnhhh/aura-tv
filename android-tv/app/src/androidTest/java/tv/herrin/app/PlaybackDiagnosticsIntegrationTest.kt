package tv.herrin.app

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/** Real Media3 + local TLS + original fixtures; no provider or external account is required. */
@RunWith(AndroidJUnit4::class)
class PlaybackDiagnosticsIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun fixtures(block: (MockWebServer, List<String>) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        val requests = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        try {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?').orEmpty()
                    requests.add(path)
                    if (path.startsWith("/status/"))
                        return MockResponse().setResponseCode(path.substringAfterLast('/').toInt())
                    val (asset, mime) = when (path) {
                        "/primary.m3u8" -> return MockResponse().setResponseCode(403)
                        "/mp4" -> "synthetic.mp4" to "video/mp4"
                        "/hls" -> "synthetic-hls.m3u8" to "application/vnd.apple.mpegurl"
                        "/synthetic-segment-00.ts" -> "synthetic-segment-00.ts" to "video/mp2t"
                        "/dash" -> "playback-diagnostics/manifest.mpd" to "application/dash+xml"
                        "/init-stream0.m4s" -> "playback-diagnostics/init-stream0.m4s" to "video/mp4"
                        "/chunk-stream0-00001.m4s" -> "playback-diagnostics/chunk-stream0-00001.m4s" to "video/iso.segment"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    val data = inst.context.assets.open(asset).use { it.readBytes() }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", mime)
                        .setBody(Buffer().write(data))
                }
            }
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            block(server, requests)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            runCatching { server.shutdown() }
        }
    }

    private fun launch(server: MockWebServer, path: String, alternate: String? = null): ActivityScenario<PlaybackTestActivity> {
        val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
            .putExtra("fixture", server.url(path).toString())
        alternate?.let { intent.putExtra("alternate", server.url(it).toString()) }
        return ActivityScenario.launch(intent)
    }

    private fun frames(scenario: ActivityScenario<PlaybackTestActivity>) {
        compose.waitUntil(30_000) {
            var rendered = false
            scenario.onActivity { rendered = (it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3 }
            rendered
        }
    }

    @Test fun extensionlessMp4HlsAndDashDecodeOriginalFrames() {
        fixtures { server, requests ->
            for (path in listOf("/mp4", "/hls", "/dash")) {
                launch(server, path).use(::frames)
            }
            assertTrue(requests.contains("/synthetic-segment-00.ts"))
            assertTrue(requests.contains("/init-stream0.m4s"))
            assertTrue(requests.contains("/chunk-stream0-00001.m4s"))
        }
    }

    @Test fun hlsPrimaryDenialDoesNotForceHlsMimeOntoExtensionlessMp4Backup() {
        fixtures { server, requests ->
            launch(server, "/primary.m3u8", "/mp4").use(::frames)
            assertTrue(requests.contains("/primary.m3u8"))
            assertTrue(requests.contains("/mp4"))
        }
    }

    @Test fun httpStatusesRemainDistinctAndDiagnosticsNeverExposeRequestCredentials() {
        fixtures { server, _ ->
            for (status in listOf(401, 403, 404, 429, 503)) {
                launch(server, "/status/$status?token=private-fixture").use {
                    compose.waitUntil(30_000) {
                        runCatching { compose.onNodeWithTag("player-error")
                            .assertTextContains("HTTP $status", substring = true) }.isSuccess
                    }
                    val text = compose.onNodeWithTag("player-error").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
                    assertFalse(text.contains("private-fixture"))
                    assertFalse(text.contains("localhost"))
                    assertFalse(text.contains("https://"))
                }
            }
        }
    }

    @Test fun rateLimitNeverAutomaticallyRequestsAnAlternate() {
        fixtures { server, requests ->
            launch(server, "/status/429", "/mp4").use {
                compose.waitUntil(30_000) {
                    runCatching { compose.onNodeWithTag("player-error")
                        .assertTextContains("HTTP 429", substring = true) }.isSuccess
                }
                assertFalse(requests.contains("/mp4"))
            }
        }
    }
}
