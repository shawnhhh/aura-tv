package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DanmakuControlsTest {
    @get:Rule val compose = createComposeRule()
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    @Test fun realTvCenterKeyOperatesToggleOpacitySpeedAndSize() {
        compose.setContent {
            var settings by remember { mutableStateOf(DanmakuSettings()) }
            DanmakuControls(settings, { settings = it })
        }
        compose.onNodeWithTag("danmaku-toggle").assertTextContains("弹幕：关", substring = true)
        press("danmaku-toggle")
        compose.onNodeWithTag("danmaku-toggle").assertTextContains("弹幕：开", substring = true)
        press("danmaku-opacity")
        compose.onNodeWithTag("danmaku-opacity").assertTextContains("100%", substring = true)
        press("danmaku-speed")
        compose.onNodeWithTag("danmaku-speed").assertTextContains("1.5×", substring = true)
        press("danmaku-size")
        compose.onNodeWithTag("danmaku-size").assertTextContains("32", substring = true)
        press("danmaku-toggle")
        compose.onNodeWithTag("danmaku-toggle").assertTextContains("弹幕：关", substring = true)
    }
}
