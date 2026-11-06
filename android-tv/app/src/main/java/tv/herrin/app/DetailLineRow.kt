package tv.herrin.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Every line owns an explicit vertical exit; spatial geometry never decides the episode region. */
@Composable
internal fun DetailLineRow(
    lines: List<Pair<String, String>>, selected: String, reduced: Boolean,
    requester: (Int) -> FocusRequester, onFocus: (Int) -> Unit,
    onDown: () -> Unit, onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEachIndexed { index, (id, title) ->
            TvButton(title, Modifier.testTag("line-$id").focusRequester(requester(index))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        onDown(); true
                    } else false
                }, reduced = reduced, selected = id == selected, onFocus = { onFocus(index) }) { onSelect(id) }
        }
    }
}
