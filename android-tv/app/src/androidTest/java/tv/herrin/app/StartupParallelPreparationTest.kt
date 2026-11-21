package tv.herrin.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StartupParallelPreparationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun networkStartsBehindCurtainAndSurvivesCurtainRemoval() {
        compose.mainClock.autoAdvance = false
        val startup = StartupPresentation()
        compose.setContent { StartupHost(startup) { /* Home deliberately waits for data. */ } }
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeByFrame(); startup.networkReady }
        compose.onNodeWithTag("splash-curtain").assertExists()
        compose.runOnIdle {
            assertTrue(startup.prepareHome)
            assertFalse(startup.homeReady)
            assertTrue(startup.showing)
            startup.homeReady = true
        }
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); !startup.showing }
        compose.onNodeWithTag("splash").assertDoesNotExist()
        compose.runOnIdle { assertTrue(startup.networkReady) }
    }
}
