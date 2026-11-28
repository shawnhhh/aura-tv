package tv.herrin.app

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction

/**
 * TV text entry has two distinct states: an arrow-key focus stop and an active
 * editor. Giving BasicTextField direct D-pad focus starts the TV IME, which can
 * intercept the *next* arrow before the Activity receives it. Only OK enters
 * editing; Back leaves it and restores the D-pad focus stop.
 */
@Composable
internal fun TvSourceInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldTag: String,
    navTag: String,
    navRequester: FocusRequester,
    editRequester: FocusRequester,
    onNavFocus: () -> Unit,
    onEditingFocus: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val session = rememberTvEditSession { runCatching { navRequester.requestFocus() } }
    fun finish() {
        keyboard?.hide()
        session.stop()
        navRequester.requestFocus()
    }
    var editorWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(session.editing) {
        if (session.editing) {
            withFrameNanos { }
            runCatching { editRequester.requestFocus() }
            keyboard?.show()
        }
    }
    Box(Modifier.fillMaxWidth().testTag(navTag)
        .onFocusChanged { if (it.isFocused) onNavFocus() }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (session.editing) false
            else if (key.isTvEditorConfirm()) {
                if (key.action == AndroidKeyEvent.ACTION_UP && !key.isCanceled) session.start()
                true
            } else if (event.type != KeyEventType.KeyDown) false else when (key.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> { onMoveUp(); true }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onMoveDown(); true }
                else -> false
            }
        }
        .focusRequester(navRequester).focusable()) {
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth()
            .testTag(fieldTag)
            .finishTvEditingOnBack(session, ::finish)
            .focusProperties { canFocus = session.editing }
            .focusRequester(editRequester)
            .onFocusChanged {
                if (it.hasFocus) editorWasFocused = true
                else if (editorWasFocused) {
                    editorWasFocused = false
                    session.stop()
                }
                onEditingFocus(it.hasFocus)
            },
            label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish() }))
    }
}
