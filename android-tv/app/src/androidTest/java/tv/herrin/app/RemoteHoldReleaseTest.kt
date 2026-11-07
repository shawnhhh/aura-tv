package tv.herrin.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class RemoteHoldReleaseTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("herrin",0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun moreThan100RepeatEventsStopMovingAfterRelease() {
        compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess }
        compose.onNodeWithTag("card-orbit").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        repeat(110) { count ->
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, count))
            SystemClock.sleep(25)
        }
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        compose.waitForIdle()
        fun focused() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map {
            it.config.getOrElse(SemanticsProperties.TestTag) { "node-${it.id}" }
        }
        val released = focused()
        assertTrue("Remote must retain an actual focus owner", released.isNotEmpty())
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("Released remote must not drain delayed navigation jobs", released, focused())
    }
}
