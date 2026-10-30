package tv.herrin.app

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M439LaunchWordmarkTest {
    // Reset before Activity creation so prior IPTV/TVBox tests cannot redirect
    // launch to a different category or bring in an unrelated persisted source.
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleWordmarkFadeRevealsUsableHomeWithoutMultiSecondHold() {
        // Splash timing excludes Android process spawn; measure from Activity
        // onCreate, NOT from first test assertion (which can run too late).
        compose.waitUntil(12_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("nav-首页").assertExists()
            }.isSuccess
        }
        val elapsed = StartupTiming.lastComposeReadyMs
        Log.i("HerrinStartup", "emulator_activity_to_interactive_ms=$elapsed")
        assertTrue("Missing real startup timing", elapsed >= 0)
        // Shared runners are noisy; this only rejects severe multi-second
        // animation regressions. The stronger 440ms policy test is a JVM test.
        assertTrue("Application-controlled startup exceeded 8s", elapsed < 8_000)
    }
}
