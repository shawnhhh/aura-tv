package tv.herrin.app

import android.view.KeyEvent

/**
 * Settings-list preview routing must never steal arrows from a focused text
 * editor. When browsing, Up/Down are deliberately handled by the compact
 * remote navigation system; when editing, Compose TextField/TV IME own them.
 */
internal object CompactSettingsKeyPolicy {
    fun intercept(
        onSettings: Boolean,
        compact: Boolean,
        editorFocused: Boolean,
        keyDown: Boolean,
        code: Int
    ): Boolean = onSettings && compact && !editorFocused && keyDown &&
        (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
}
