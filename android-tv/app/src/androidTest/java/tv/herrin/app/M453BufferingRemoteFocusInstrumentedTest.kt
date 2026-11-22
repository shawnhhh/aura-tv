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

    @Test fun productionMinimalControlsMenusRetainTheSoleSession() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val media = PlaybackMedia(server.url("/clean-player.mp4").toString(), displayTitle = "Original fixture")
        val players = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        try {
            compose.setContent {
                AuthorizedPlaybackPanel(media = media, onPlayer = { players.add(it) }, onBack = {})
            }
            compose.waitUntil(7_000) { runCatching {
                compose.onNodeWithTag("transport-toggle").assertExists()
                compose.onNodeWithTag("transport-speed").assertExists()
            }.isSuccess }
            compose.onNodeWithText("Original fixture").assertExists()
            for (tag in listOf("transport-speed", "transport-audio", "transport-subtitle", "transport-episodes",
                "transport-rewind", "transport-forward", "player-position-label", "player-duration-label"))
                compose.onNodeWithTag(tag).assertExists()
            for (tag in listOf("player-back", "danmaku-controls", "transport-repeat", "transport-skip-silence",
                "player-quality-label", "transport-viewport-mode", "transport-more", "player-verification-toggle"))
                compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("kernel-settings").assertDoesNotExist()
            compose.onNodeWithTag("transport-speed")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("2.0×").assertExists()
            compose.onNodeWithText("0.5×").assertExists()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("player-menu-popup").assertDoesNotExist() }.isSuccess }
            compose.onNodeWithTag("transport-toggle").assertExists()
            compose.onNodeWithTag("transport-episodes").performClick()
            compose.onNodeWithText("暂无可用版本或集数").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("transport-episodes").assertIsFocused() }.isSuccess }
            compose.onNodeWithTag("transport-audio").performClick()
            compose.onNodeWithText("暂无可用音轨").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("transport-audio").assertIsFocused() }.isSuccess }
            compose.onNodeWithTag("transport-subtitle").performClick()
            compose.onNodeWithText("关闭字幕").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals("Menus retain sole player", 1, players.size) }
        } finally { server.shutdown() }
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
