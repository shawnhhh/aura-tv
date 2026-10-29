package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Injects actual remote-key events into the full-screen TV player, not just a pure navigation model. */
@RunWith(AndroidJUnit4::class)
class M420LiveDpadInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @get:Rule(order = 0) val source = object : ExternalResource() {
        override fun before() {
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
            val channels = listOf(
                LiveChannel("CCTV1", "https://127.0.0.1:1/cctv1.m3u8", "央视"),
                LiveChannel("CCTV-1 高清", "https://127.0.0.1:1/cctv1-hd.m3u8", "央视"),
                LiveChannel("CCTV2", "https://127.0.0.1:1/cctv2.m3u8", "央视")
            )
            assertTrue(TvBoxLiveStore(context).save(channels, allowHttp = false))
            Preferences(context).tvBoxLiveActive = true
        }

        override fun after() {
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    @Test fun fullscreenUpDownCyclesUniqueProgramsWithoutOpeningLinePicker() {
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("nav-直播").assertExists()
            }.isSuccess
        }
        compose.onNodeWithTag("nav-直播")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("live-fullscreen").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("live-playing-name").assertTextEquals("CCTV1")
        compose.onNodeWithTag("live-player-focus")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("live-playing-name").assertTextEquals("CCTV2")
        compose.onNodeWithTag("live-channel-drawer").assertDoesNotExist()

        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("live-playing-name").assertTextEquals("CCTV1")
        compose.onNodeWithTag("live-channel-drawer").assertDoesNotExist()
    }
}
