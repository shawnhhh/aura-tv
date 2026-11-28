package tv.herrin.app

import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation

internal class TvEditSession {
    var editing by mutableStateOf(false)
        private set
    fun start() { editing = true }
    fun stop() { editing = false }
}

@Composable
internal fun rememberTvEditSession(onFinish: () -> Unit = {}): TvEditSession {
    val state = remember { TvEditSession() }
    val keyboard = LocalSoftwareKeyboardController.current
    val completed by rememberUpdatedState(onFinish)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var observedIme by remember { mutableStateOf(false) }
    LaunchedEffect(state.editing, imeVisible) {
        if (!state.editing) observedIme = false
        else if (imeVisible) observedIme = true
        else if (observedIme) {
            state.stop()
            completed()
        }
    }
    BackHandler(state.editing) {
        keyboard?.hide()
        state.stop()
        completed()
    }
    return state
}

internal fun KeyEvent.isTvEditorConfirm(): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/** D-pad focus and text editing share the same visual field and focus restore target. */
@Composable
internal fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onEditingChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val useRemote = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    if (!useRemote) {
        OutlinedTextField(value, onValueChange, modifier, enabled = enabled, label = label,
            singleLine = singleLine, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
        return
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val session = rememberTvEditSession()
    var confirmPressed by remember { mutableStateOf(false) }
    val reportEditing by rememberUpdatedState(onEditingChanged)
    LaunchedEffect(session.editing) {
        reportEditing(session.editing)
        if (session.editing) {
            withFrameNanos { }
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { if (session.editing) reportEditing(false) } }
    OutlinedTextField(value, onValueChange, modifier
        .onFocusChanged { state ->
            if (!state.hasFocus) {
                confirmPressed = false
                if (session.editing) {
                    keyboard?.hide()
                    session.stop()
                }
            }
        }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (confirmPressed && key.isTvEditorConfirm() && key.action == KeyEvent.ACTION_UP) {
                confirmPressed = false
                if (!key.isCanceled) session.start()
                true
            } else if (session.editing || !enabled) false
            else if (key.isTvEditorConfirm()) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) confirmPressed = true
                true
            } else if (key.action == KeyEvent.ACTION_DOWN) {
                val direction = when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                    KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                    KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                    KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                    else -> null
                }
                direction != null && focus.moveFocus(direction)
            } else false
        }
        .semantics {
            // Accessibility can set an editable value directly without a hardware OK gesture.
            if (enabled) setText { next -> onValueChange(next.text); true }
        },
        label = label, singleLine = singleLine, enabled = enabled, readOnly = !session.editing,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            session.stop()
            keyboardActions.onDone?.invoke(this)
        }))
}
