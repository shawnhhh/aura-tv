package tv.herrin.app

import android.os.SystemClock
import android.util.Log
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

/**
 * Unlike the P95 test, this does NOT wait for Compose idle after each key.
 * This catches stale focus jobs and delayed horizontal rail navigation.
 */
@RunWith(AndroidJUnit4::class)
class M443RemoteBurstWithoutIdleTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun rapidTwentyKeyReversalsRestoreLastRequestedPosterWithoutIdleWaits() {
        compose.waitUntil(12_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("card-orbit").assertIsDisplayed()
            }.isSuccess
        }
        compose.onNodeWithTag("card-orbit")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag("card-orbit").assertIsFocused()

        val started = SystemClock.elapsedRealtimeNanos()
        repeat(10) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_RIGHT)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                KeyEvent.KEYCODE_DPAD_LEFT)
        }
        // Only after all twenty OS key events: no per-key Compose idle waits.
        compose.waitUntil(8_000) {
            runCatching { compose.onNodeWithTag("card-orbit").assertIsFocused() }.isSuccess
        }
        val totalMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        Log.i("HerrinPerf", "m443_rapid_burst_events=20 last_focus_correct=1 " +
            "burst_to_final_focus_ms=$totalMs")
        assertTrue("20-event key burst should not stall the test process", totalMs < 8_000)
    }
}
