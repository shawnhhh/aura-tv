package tv.herrin.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Compose/Media3 player and native Android D-pad injection.
 *
 * The local server accepts the connection but NEVER supplies a first frame:
 * DOWN must nevertheless bring keyboard focus to the playable transport.
 * This catches regressions where the UI waits for decodedOutputBufferCount.
 */
@RunWith(AndroidJUnit4::class)
class M453BufferingRemoteFocusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun ordinaryPlayerHasNoUpperLeftTextNorRelocatedDebugButtons() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val media = PlaybackMedia(server.url("/clean-player.mp4").toString())
        try {
            // This is the actual consumer player entry, NOT PlaybackTestActivity
            // with its explicit isolated testDiagnostics=true.
            compose.setContent {
                AuthorizedPlaybackPanel(media = media, onBack = {})
            }
            compose.waitUntil(7_000) {
                runCatching {
                    compose.onNodeWithTag("transport-toggle").assertExists()
                    compose.onNodeWithTag("transport-more").assertExists()
                }.isSuccess
            }
            // Consumer playback must start as a short, two-row control strip:
            // extra settings and all developer diagnostics are not composed.
            compose.onNodeWithTag("transport-speed").assertDoesNotExist()
            compose.onNodeWithTag("transport-audio").assertDoesNotExist()
            compose.onNodeWithTag("transport-subtitle").assertDoesNotExist()
            compose.onNodeWithTag("transport-viewport-mode").assertDoesNotExist()
            compose.onNodeWithTag("danmaku-controls").assertDoesNotExist()
            compose.onNodeWithTag("player-verification-toggle").assertDoesNotExist()
            compose.onNodeWithTag("player-back-control").assertDoesNotExist()
            compose.onNodeWithTag("player-verification-status").assertDoesNotExist()
            compose.onNodeWithTag("player-journey-status").assertDoesNotExist()

            // Actual native TV remote Center opens advanced settings, not a
            // test-only performClick on the additional "more" button.
            compose.onNodeWithTag("transport-more")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            compose.onNodeWithTag("transport-more").assertIsFocused()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithTag("transport-viewport-mode").assertExists()
                    compose.onNodeWithTag("transport-speed").assertExists()
                    compose.onNodeWithTag("danmaku-controls").assertExists()
                }.isSuccess
            }
            val before = compose.onNodeWithTag("transport-viewport-mode")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString(" ") { it.text }
            compose.onNodeWithTag("transport-viewport-mode").performClick()
            val after = compose.onNodeWithTag("transport-viewport-mode")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString(" ") { it.text }
            org.junit.Assert.assertNotEquals("Aspect mode must toggle without a player restart",
                before, after)

            // Return to compact HUD without removing or relocating the user's
            // explicitly deleted upper-left technical labels.
            compose.onNodeWithTag("transport-more")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithTag("transport-viewport-mode").assertDoesNotExist()
                    compose.onNodeWithTag("transport-speed").assertDoesNotExist()
                }.isSuccess
            }
            compose.onNodeWithTag("player-verification-toggle").assertDoesNotExist()
            compose.onNodeWithTag("player-back-control").assertDoesNotExist()
        } finally {
            server.shutdown()
        }
    }

    @Test fun remoteDownFocusesTransportEvenWhenNoMediaBytesHaveArrived() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        // Resolve the MockWebServer URL on the instrumentation test thread.
        // MockWebServer.url() may perform a reverse hostname lookup and must
        // never execute inside the Compose main-thread setContent lambda.
        val mockMedia = PlaybackMedia(server.url("/mock-buffering.mp4").toString())
        try {
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = mockMedia,
                    onBack = {}
                )
            }
            compose.waitUntil(6_000) {
                runCatching {
                    compose.onNodeWithTag("player-root-focus").assertExists()
                    compose.onNodeWithTag("transport-toggle").assertExists()
                }.isSuccess
            }
            // Explicit native player Root focus, not a test-only semantic click
            // on transport. DOWN must be the first action that selects it.
            compose.onNodeWithTag("player-root-focus")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            compose.onNodeWithTag("player-root-focus").assertIsFocused()

            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithTag("transport-toggle").assertIsFocused()
                }.isSuccess
            }
            compose.onNodeWithTag("transport-toggle").assertIsFocused()
            // This MockWebServer never supplies a video frame, intentionally:
            // success proves the controls do not depend on decoding success.
        } finally {
            server.shutdown()
        }
    }
}
