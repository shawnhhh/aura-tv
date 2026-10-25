package tv.herrin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic clock assertions on the same Splash composable used by MainActivity. */
class HerrinSplashTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/herrin-test-evidence && screencap -p /data/local/tmp/herrin-test-evidence/$name.png").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
    @Test fun lettersCollapseToHThenHomeCallback() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(false) { completed = true } } } }
        compose.onNodeWithText("H").assertIsDisplayed()
        compose.onNodeWithText("E").assertIsDisplayed()
        compose.onAllNodesWithText("R").assertCountEquals(2)
        compose.onNodeWithText("I").assertIsDisplayed()
        compose.onNodeWithText("N").assertIsDisplayed()
        screenshot("splash-HERRIN")
        compose.mainClock.advanceTimeBy(2600)
        compose.onNodeWithText("H").assertIsDisplayed()
        compose.onNodeWithText("E").assertIsNotDisplayed()
        compose.onNodeWithText("N").assertIsNotDisplayed()
        assertFalse(completed)
        screenshot("splash-H")
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun skipCompletesImmediately() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(false) { completed = true } } } }
        compose.onNodeWithText("跳过 · 确定").performClick()
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun reducedMotionCompletesWithoutWaitingForAnimation() {
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(true) { completed = true } } } }
        compose.runOnIdle { assertTrue(completed) }
    }
}
