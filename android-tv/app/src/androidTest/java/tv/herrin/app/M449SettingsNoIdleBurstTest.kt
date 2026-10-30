package tv.herrin.app

import android.os.SystemClock
import android.view.KeyEvent
import android.util.Log
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

/**
 * Native D-pad burst regression on the actual settings LazyColumn.
 * Do not await Compose idle between four inputs. This detects when
 * asynchronous focus left the following presses routing from the old row.
 */
@RunWith(AndroidJUnit4::class)
class M449SettingsNoIdleBurstTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourRapidDownKeysMoveFourRowsAndBackWithoutOpeningIme() {
        compose.waitUntil(12_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("settings").assertIsDisplayed()
            }.isSuccess
        }
        compose.onNodeWithTag("settings")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
            KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("screen-first").assertIsFocused() }.isSuccess
        }

        val started = SystemClock.elapsedRealtime()
        repeat(4) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_DOWN)
        }
        compose.waitUntil(8_000) {
            runCatching {
                compose.onNodeWithTag("tvbox-live-direct-load").assertIsFocused()
            }.isSuccess
        }
        val downMs = SystemClock.elapsedRealtime() - started
        compose.onNodeWithTag("tvbox-live-direct-load").assertIsFocused()

        // Move back up three rows without intermediate idle waits. The
        // navigation intent must reverse the requested target, not the
        // focus that happened to be attached on the first key.
        repeat(3) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_UP)
        }
        compose.waitUntil(8_000) {
            runCatching {
                compose.onNodeWithTag("tvbox-warehouse-nav").assertIsFocused()
            }.isSuccess
        }
        val totalMs = SystemClock.elapsedRealtime() - started
        Log.i("HerrinPerf", "m449_settings_burst_events=7 final_focus_correct=1 " +
            "four_down_ms=$downMs total_ms=$totalMs")
        assertTrue("Settings no-idle D-pad burst severe stall", totalMs < 15_000)
    }
}
