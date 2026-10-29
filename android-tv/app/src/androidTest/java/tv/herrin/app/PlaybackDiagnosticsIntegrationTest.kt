package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.json.JSONObject
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
                    if (path.startsWith("/spider/")) {
                        // Require CatVod playerContent headers on BOTH HLS playlist and TS requests.
                        if (request.getHeader("Referer") != "https://authorized.example.test/library/" ||
                            request.getHeader("User-Agent") != "HERRIN-fixture/1.0") {
                            return MockResponse().setResponseCode(403)
                        }
                        val name = path.removePrefix("/spider/")
                        val asset = when {
                            name == "playlist" -> "synthetic-hls.m3u8"
                            Regex("synthetic-segment-[0-9]{2}\\.ts").matches(name) -> name
                            else -> return MockResponse().setResponseCode(404)
                        }
                        val mime = if (name == "playlist") "application/vnd.apple.mpegurl" else "video/mp2t"
                        return MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", mime)
                            .setBody(Buffer().write(inst.context.assets.open(asset).use { it.readBytes() }))
                    }
                    if (path.startsWith("/status/"))
                        return MockResponse().setResponseCode(path.substringAfterLast('/').toInt())
                    val (asset, mime) = when (path) {
                        "/primary.m3u8" -> return MockResponse().setResponseCode(403)
                        "/mp4" -> "synthetic.mp4" to "video/mp4"
                        "/remote" -> "synthetic-remote.mp4" to "video/mp4"
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

    @Test fun tvRemoteDownFocusesControlsWhileLeftRightSeekAndQualityUsesMedia3Size() {
        fixtures { server, _ ->
            launch(server, "/remote").use { scenario ->
                frames(scenario)
                TvVisibleFrameEvidence.verify(inst, "m437-remote-visible-frame")
                // The overlay may auto-hide during slow CI decoding; re-open
                // it using the actual remote instead of depending on timing.
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("player-quality-label").assertExists() }.isSuccess
                }
                compose.onNodeWithTag("player-quality-label")
                    .assertTextContains("320×180", substring = true)
                    .assertTextContains("24 fps", substring = true)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess
                }
                var position = -1L
                scenario.onActivity { it.player.pause(); it.player.seekTo(12_000L) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                scenario.onActivity { position = it.player.currentPosition }
                assertEquals("Remote Right must seek exactly ten seconds", 22_000L, position)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("player-position-label")
                        .assertTextContains("00:22") }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity { position = it.player.currentPosition }
                assertEquals("Remote Left must rewind exactly ten seconds", 12_000L, position)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("player-position-label")
                        .assertTextContains("00:12") }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess
                }
            }
        }
    }

    @Test fun realRemoteDownReachesLineAndEpisodeActions() {
        fixtures { server, _ ->
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("fixture", server.url("/mp4").toString())
                .putExtra("withSelectionActions", true)
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                frames(scenario)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-lines").assertIsFocused() }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { assertEquals(1, it.lineSelections) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-episodes").assertIsFocused() }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { assertEquals(1, it.episodeSelections) }
            }
        }
    }

    @Test fun focusedControlsHideAfterIdleAndRealRemoteReopensThem() {
        fixtures { server, _ ->
            launch(server, "/remote").use { scenario ->
                scenario.onActivity { it.player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE }
                frames(scenario)
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess
                }
                compose.waitUntil(15_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertDoesNotExist() }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(8_000) {
                    runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(8_000) {
                    var paused = false
                    scenario.onActivity { paused = !it.player.playWhenReady }
                    paused
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(8_000) {
                    var playing = false
                    scenario.onActivity { playing = it.player.playWhenReady }
                    playing
                }
            }
        }
    }

    @Test fun extensionlessMp4HlsAndDashDecodeOriginalFrames() {
        fixtures { server, requests ->
            for (path in listOf("/mp4", "/hls", "/dash")) {
                launch(server, path).use { scenario ->
                    frames(scenario)
                    TvVisibleFrameEvidence.verify(inst, "m437-${path.removePrefix("/")}-visible-frame")
                }
            }
            assertTrue(requests.contains("/synthetic-segment-00.ts"))
            assertTrue(requests.contains("/init-stream0.m4s"))
            assertTrue(requests.contains("/chunk-stream0-00001.m4s"))
        }
    }

    @Test fun spiderPlaybackJsonHeadersAndExtensionlessHlsDecodeRealVideoSegments() {
        fixtures { server, requests ->
            val playerJson = JSONObject()
                .put("parse", 0)
                .put("url", server.url("/spider/playlist").toString())
                .put("format", "hls")
                .put("header", JSONObject()
                    .put("Referer", "https://authorized.example.test/library/")
                    .put("User-Agent", "HERRIN-fixture/1.0"))
                .toString()
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("spiderJson", playerJson)
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                frames(scenario)
                assertTrue("Spider manifest must be requested", requests.contains("/spider/playlist"))
                assertTrue("Spider segment requests must preserve the session headers",
                    requests.any { it.startsWith("/spider/synthetic-segment-") && it.endsWith(".ts") })
                compose.onNodeWithTag("player-verification-toggle").performClick()
                compose.waitUntil(30_000) {
                    runCatching { compose.onNodeWithTag("player-journey-status")
                        .assertTextContains("已输出视频帧", substring = true) }.isSuccess
                }
            }
        }
    }

    @Test fun firstFrameJourneyIsVisibleInVerificationWithoutExposingMediaAddress() {
        fixtures { server, _ ->
            launch(server, "/mp4").use { scenario ->
                frames(scenario)
                compose.onNodeWithTag("player-verification-toggle").performClick()
                compose.waitUntil(30_000) {
                    runCatching { compose.onNodeWithTag("player-journey-status")
                        .assertTextContains("已输出视频帧", substring = true) }.isSuccess
                }
                val text = compose.onNodeWithTag("player-journey-status").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
                assertTrue(text.contains("第 1/1 条地址"))
                assertFalse(text.contains("localhost"))
                assertFalse(text.contains("https://"))
                assertFalse(text.contains("token"))
            }
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
