package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry

internal fun SemanticsNodeInteraction.editRemoteText(value: String): SemanticsNodeInteraction {
    val needsOk = fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsEditable) == false
    if (needsOk) {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    performTextReplacement(value)
    if (needsOk) performImeAction()
    return this
}
