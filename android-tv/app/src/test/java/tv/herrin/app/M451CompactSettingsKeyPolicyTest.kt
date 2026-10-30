package tv.herrin.app

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class M451CompactSettingsKeyPolicyTest {
    private fun route(editor: Boolean, code: Int, down: Boolean = true) =
        CompactSettingsKeyPolicy.intercept(
            onSettings = true, compact = true, editorFocused = editor,
            keyDown = down, code = code)

    @Test fun nonEditingFocusStopsOwnVerticalRemoteNavigation() {
        assertTrue(route(false, KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(route(false, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(route(false, KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(route(false, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test fun activeSourceTextEditorOwnsAllArrowKeys() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertFalse("Text editor must not lose remote cursor/navigation keys",
                route(true, code))
        }
    }

    @Test fun noPreviewRoutingOnKeyUpOrOtherScreens() {
        assertFalse(route(false, KeyEvent.KEYCODE_DPAD_DOWN, down = false))
        assertFalse(CompactSettingsKeyPolicy.intercept(
            onSettings = false, compact = true, editorFocused = false,
            keyDown = true, code = KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(CompactSettingsKeyPolicy.intercept(
            onSettings = true, compact = false, editorFocused = false,
            keyDown = true, code = KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(route(false, KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
