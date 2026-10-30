package tv.herrin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.KeyEvent
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
        for (command in listOf("mkdir -p /data/local/tmp/herrin-test-evidence", "screencap -p /data/local/tmp/herrin-test-evidence/$name.png")) {
            i.uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
    }
    @Test fun completeHerrinWordmarkStaysWholeUntilFastFade() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Splash(false) { completed = true } }
            }
        }
        compose.onNodeWithContentDescription("HERRIN").assertIsDisplayed()
        compose.onNodeWithTag("splash-wordmark").assertExists()
        // There is never a single-letter H-only screen.
        compose.onNodeWithText("H").assertDoesNotExist()
        screenshot("splash-HERRIN")
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertTrue("No multi-second branding hold", completed) }
        compose.onNodeWithContentDescription("HERRIN").assertExists()
    }
    @Test fun skipCompletesImmediately() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(false) { completed = true } } } }
        compose.onNodeWithText("跳过 · 确定").assertDoesNotExist()
        compose.onNodeWithTag("splash").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun reducedMotionCompletesWithoutWaitingForAnimation() {
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(true) { completed = true } } } }
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun exitCurtainOverlapsHomeBeforeCompleting() {
        compose.mainClock.autoAdvance = false
        var exiting by mutableStateOf(false)
        var finished = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    Text("首页内容", Modifier.testTag("home-underlay"))
                    SplashExitCurtain(false, exiting, onReady = { exiting = true },
                        onFinished = { finished = true })
                }
            }
        }
        compose.runOnIdle { exiting = true }
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("home-underlay").assertExists()
        compose.onNodeWithTag("splash-curtain").assertExists()
        compose.runOnIdle { assertFalse(finished) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertTrue(finished) }
    }
}
