package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Real decoder/source/retry acceptance using only bundled original media and local TLS. */
@RunWith(AndroidJUnit4::class)
class AuthorizedPlaybackJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun asset(name: String) = inst.context.assets.open(name).use { it.readBytes() }

    private fun until(message: String, timeoutMs: Long = 23_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(90)
        }
        fail(message)
    }

    private fun withOriginalTlsFixtures(
        block: (server: MockWebServer, paths: List<String>, allowRetry: AtomicBoolean,
                opener: (String) -> HttpURLConnection) -> Unit
    ) {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val mp4 = asset("synthetic.mp4")
        val hls = asset("synthetic-hls.m3u8")
        val segments = (0..9).mapNotNull { n ->
            val name = "synthetic-segment-${n.toString().padStart(2, '0')}.ts"
            runCatching { name to asset(name) }.getOrNull()
        }.toMap()
        assertTrue("Original HLS transport fixture must exist", segments.isNotEmpty())
        val paths = CopyOnWriteArrayList<String>()
        val allowRetry = AtomicBoolean(false)
        val server = MockWebServer()
        try {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?').orEmpty()
                    paths.add(path)
                    val now = OffsetDateTime.now(ZoneOffset.UTC)
                    val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
                    val xml = """<tv><programme channel="one" start="${now.minusMinutes(10).format(format)}" stop="${now.plusMinutes(10).format(format)}"><title>原创建模节目</title></programme></tv>"""
                    val playlist = """#EXTM3U
                        #EXTINF:-1 tvg-id="one" group-title="自制测试",测试频道一
                        ${server.url("/first.mp4")}
                        #EXTINF:-1 tvg-id="two" group-title="自制测试",测试频道二
                        ${server.url("/second.m3u8")}
                    """.trimIndent()
                    val (data, contentType) = when (path) {
                        "/channels.m3u" -> playlist.toByteArray() to "audio/x-mpegurl"
                        "/guide.xml" -> xml.toByteArray() to "application/xml"
                        "/first.mp4" -> mp4 to "video/mp4"
                        "/second.m3u8" -> hls to "application/vnd.apple.mpegurl"
                        "/flaky.mp4" -> if (allowRetry.get()) mp4 to "video/mp4"
                            else return MockResponse().setResponseCode(503)
                        else -> segments[path.removePrefix("/")]?.let { it to "video/mp2t" }
                            ?: return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", contentType)
                        .setHeader("Cache-Control", "no-store")
                        .setBody(Buffer().write(data))
                }
            }
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            val open: (String) -> HttpURLConnection = { link ->
                (URL(link).openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = clientTls.sslSocketFactory()
                }
            }
            block(server, paths, allowRetry, open)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            runCatching { server.shutdown() }
        }
    }

    @Test fun authorizedM3uGuideAndLiveLikeChannelSwitchDecodeRealFrames() {
        withOriginalTlsFixtures { server, paths, _, open ->
            val channels = (runBlocking {
                RemoteLivePlaylistRepository(open, timeoutMs = 3000)
                    .load(server.url("/channels.m3u").toString())
            } as LivePlaylistResult.Ready).channels
            val guide = (runBlocking {
                RemoteLiveEpgRepository(open, timeoutMs = 3000)
                    .load(server.url("/guide.xml").toString())
            } as LiveEpgResult.Ready).programmes
            assertEquals(2, channels.size)
            assertNotEquals("Channel-switch fixture URLs must differ", channels[0].url, channels[1].url)
            assertEquals("原创建模节目", AuthorizedXmlTv.current(
                guide, channels[0].tvgId, System.currentTimeMillis())?.title)
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", channels[0].url)
            ).use { scenario ->
                until("First HTTPS channel must produce decoded video frames") {
                    var rendered = 0
                    scenario.onActivity { rendered = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                    rendered > 3
                }
                var first: ExoPlayer? = null
                scenario.onActivity {
                    first = it.player
                    it.switchFixture(channels[1].url)
                }
                until("Switch must create a new decoder and render the second TLS HLS channel") {
                    var renderedSecond = false
                    scenario.onActivity {
                        renderedSecond = it.player !== first &&
                            (it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3
                    }
                    renderedSecond
                }
                until("First channel player must be released when switching") {
                    var released = false
                    scenario.onActivity { released = first?.isReleased == true }
                    released
                }
                assertTrue(paths.contains("/first.mp4"))
                assertTrue(paths.contains("/second.m3u8"))
                assertTrue("HLS switch must request actual video segments",
                    paths.any { it.endsWith(".ts") })
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                until("Back after channel switch must release active decoder") {
                    var released = false
                    scenario.onActivity { released = it.player.isReleased }
                    released
                }
            }
        }
    }

    @Test fun remoteRetryAfterTls503ActuallyRestartsVideoDecoding() {
        withOriginalTlsFixtures { server, paths, allowRetry, _ ->
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", server.url("/flaky.mp4").toString())
            ).use { scenario ->
                compose.waitUntil(30_000) {
                    runCatching { compose.onNodeWithTag("player-error").assertExists() }.isSuccess
                }
                compose.onNodeWithTag("player-retry").assertIsDisplayed()
                allowRetry.set(true)
                compose.onNodeWithTag("player-retry")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.waitForIdle()
                compose.onNodeWithTag("player-retry").assertIsFocused()
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                until("Remote retry must decode HTTPS MP4 frames after prior HTTP 503") {
                    var readyAndRendered = false
                    scenario.onActivity { activity ->
                        readyAndRendered = activity.player.playbackState == Player.STATE_READY &&
                            (activity.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3
                    }
                    readyAndRendered
                }
                assertTrue("Flaky fixture should have been requested multiple times",
                    paths.count { it == "/flaky.mp4" } >= 2)
                compose.onNodeWithTag("player-verification-toggle").performClick()
                compose.onNodeWithTag("player-verification-status")
                    .assertTextContains("已输出视频帧：", substring = true)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                until("Back after recovery must release decoder") {
                    var released = false
                    scenario.onActivity { released = it.player.isReleased }
                    released
                }
            }
        }
    }
}
