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

    @Test fun remoteDownFocusesTransportEvenWhenNoMediaBytesHaveArrived() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = PlaybackMedia(server.url("/mock-buffering.mp4").toString()),
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
