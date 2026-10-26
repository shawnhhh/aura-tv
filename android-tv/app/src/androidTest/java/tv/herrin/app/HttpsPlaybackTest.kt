package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.C
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
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Tests real TLS socket transport and Media3 video decoding against locally served,
 * original FFmpeg test patterns. This is NOT a public CDN or DRM/HLS provider test.
 *
 * The temporary trust factory is installed only inside this instrumented test and
 * restored in finally. Production certificates and TLS verification remain untouched.
 */
@RunWith(AndroidJUnit4::class)
class HttpsPlaybackTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun asset(name: String) = inst.context.assets.open(name).use { it.readBytes() }

    private fun runPlayback(hls: Boolean) {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        val requestedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val mp4 = asset("synthetic.mp4")
            val captions = if (hls) asset("synthetic.vtt") else byteArrayOf()
            val playlist = if (hls) asset("synthetic-hls.m3u8") else byteArrayOf()
            val segments = if (hls) (0..9).mapNotNull { n ->
                val name = "synthetic-segment-${n.toString().padStart(2, '0')}.ts"
                runCatching { name to asset(name) }.getOrNull()
            }.toMap() else emptyMap()
            if (hls) {
                assertTrue("HLS playlist fixture missing", playlist.isNotEmpty())
                assertTrue("HLS media segments missing", segments.isNotEmpty())
            }
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?') ?: ""
                    requestedPaths.add(path)
                    val (bytes, type) = when {
                        path == "/sample.mp4" -> mp4 to "video/mp4"
                        path == "/sample.m3u8" -> playlist to "application/vnd.apple.mpegurl"
                        path == "/captions.vtt" -> captions to "text/vtt"
                        path.startsWith("/") && segments.containsKey(path.removePrefix("/")) ->
                            segments.getValue(path.removePrefix("/")) to "video/mp2t"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", type)
                        .setHeader("Cache-Control", "no-store")
                        .setBody(Buffer().write(bytes))
                }
            }
            server.start()
            val url = server.url(if (hls) "/sample.m3u8" else "/sample.mp4").toString()
            assertNotNull(AuthorizedUrls.https(url))

            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("fixture", url)
            if (hls) intent.putExtra("subtitle", server.url("/captions.vtt").toString())
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                var initialized = 0
                var rendered = 0
                var playerError: String? = null
                val end = System.currentTimeMillis() + 25_000
                while (System.currentTimeMillis() < end) {
                    scenario.onActivity { a ->
                        initialized = a.player.videoDecoderCounters?.decoderInitCount ?: 0
                        rendered = a.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        playerError = a.player.playerError?.errorCodeName
                    }
                    if (playerError != null) fail("TLS media decode failed: $playerError")
                    if (initialized > 0 && rendered > 3) break
                    Thread.sleep(100)
                }
                assertTrue("HTTPS ${if (hls) "HLS" else "MP4"} must initialize video decoder",
                    initialized > 0)
                assertTrue("HTTPS ${if (hls) "HLS" else "MP4"} must render real frames",
                    rendered > 3)
                assertTrue("Media3 must actually request the TLS fixture", server.requestCount > 0)
                assertTrue("Media3 must enter READY", scenarioState(scenario, androidx.media3.common.Player.STATE_READY))
                if (hls) {
                    val tracksDeadline = System.currentTimeMillis() + 8000L
                    var selected = false
                    while (System.currentTimeMillis() < tracksDeadline) {
                        scenario.onActivity { activity ->
                            val available = PlaybackTransport.options(activity.player, C.TRACK_TYPE_TEXT)
                            if (available.isNotEmpty()) {
                                if (available.none { it.selected }) {
                                    PlaybackTransport.cycle(activity.player, C.TRACK_TYPE_TEXT)
                                }
                                selected = PlaybackTransport.options(activity.player, C.TRACK_TYPE_TEXT)
                                    .any { it.selected }
                            }
                        }
                        if (selected) break
                        Thread.sleep(70)
                    }
                    assertTrue("HTTPS WebVTT track must become available and selected", selected)
                    // Track discovery can complete near the end of this short 6-second
                    // synthetic fixture. Rewind the real decoder after enabling text
                    // to guarantee that the entire WebVTT cue window is exercised.
                    scenario.onActivity {
                        it.player.pause()
                        it.player.seekTo(0L)
                        it.player.play()
                    }
                    val captionDeadline = System.currentTimeMillis() + 9000L
                    var decodedCues = false
                    var finalPosition = -1L
                    var finalState = -1
                    var observedCues = emptyList<String>()
                    while (System.currentTimeMillis() < captionDeadline) {
                        scenario.onActivity {
                            observedCues = it.cueHistory.toList()
                            decodedCues = observedCues.any { text ->
                                text.contains("HERRIN original subtitle testing only")
                            }
                            finalPosition = it.player.currentPosition
                            finalState = it.player.playbackState
                        }
                        if (decodedCues) break
                        Thread.sleep(70)
                    }
                    assertTrue("HLS must fetch real TLS WebVTT file: paths=" + requestedPaths,
                        requestedPaths.contains("/captions.vtt"))
                    assertTrue("Media3 must decode HTTPS WebVTT cues: position=" +
                        finalPosition + " state=" + finalState + " cues=" + observedCues +
                        " paths=" + requestedPaths, decodedCues)
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                scenario.onActivity { assertFalse(it.player.playWhenReady) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                val releasedEnd = System.currentTimeMillis() + 7_000
                var released = false
                while (System.currentTimeMillis() < releasedEnd) {
                    scenario.onActivity { released = it.player.isReleased }
                    if (released) break
                    Thread.sleep(75)
                }
                assertTrue("Back must release network player", released)
                android.util.Log.i("HerrinHttps", "tls=true hls=$hls decoderInit=$initialized rendered=$rendered requests=${server.requestCount}")
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            runCatching { server.shutdown() }
        }
    }

    private fun scenarioState(
        scenario: ActivityScenario<PlaybackTestActivity>, target: Int
    ): Boolean {
        var observed = false
        scenario.onActivity { observed = target in it.states }
        return observed
    }

    @Test fun tlsMp4ActuallyRendersFrames() = runPlayback(hls = false)
    @Test fun tlsHlsActuallyRendersFrames() = runPlayback(hls = true)
}
