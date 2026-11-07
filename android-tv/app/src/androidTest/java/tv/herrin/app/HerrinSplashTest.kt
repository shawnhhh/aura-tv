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
        compose.mainClock.advanceTimeBy(2100)
        compose.runOnIdle { assertTrue("Complete reveal precedes readiness callback", completed) }
        compose.onNodeWithContentDescription("HERRIN").assertExists()
    }
    @Test fun homePreparationRunsOnceAndOnlyAfterCompleteWordmarkReveal() {
        compose.mainClock.autoAdvance = false
        var prepared = 0
        var ready = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HerrinWordmarkSplash(false, done = { ready++ }, onVisible = { prepared++ })
            }
        }
        compose.mainClock.advanceTimeBy(850)
        compose.runOnIdle {
            assertEquals("Do not mount the heavy home halfway through the logo animation", 0, prepared)
            assertEquals(0, ready)
        }
        compose.mainClock.advanceTimeBy(1_300)
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(1, ready)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals("Repeated frame ticks must not restart heavy home creation", 1, prepared)
            assertEquals(1, ready)
        }
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
        val initialLeft = compose.onNodeWithTag("splash-curtain")
            .fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { exiting = true }
        // Let the LaunchedEffect start its Animatable before advancing the
        // deterministic frame clock. Otherwise the first frame can consume
        // the entire advance just to schedule the slide coroutine.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("home-underlay").assertExists()
        compose.onNodeWithTag("splash-curtain").assertExists()
        val shiftedLeft = compose.onNodeWithTag("splash-curtain")
            .fetchSemanticsNode().boundsInRoot.left
        assertEquals("Wordmark remains fixed during fade", initialLeft, shiftedLeft, 0.1f)
        compose.runOnIdle { assertFalse(finished) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertTrue(finished) }
    }
    @Test fun reducedMotionStillExitsWhenHomeBecomesReadyLater() {
        var ready by mutableStateOf(false)
        var released = false
        compose.setContent {
            MaterialTheme { SplashExitCurtain(true, false, onReady = { released = true },
                onFinished = {}, homeReady = ready) }
        }
        compose.runOnIdle { assertFalse(released); ready = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(released) }
    }
}
