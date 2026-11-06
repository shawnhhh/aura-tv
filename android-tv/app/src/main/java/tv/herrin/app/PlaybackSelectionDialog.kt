package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Changes only the original provider's episode/line identities. No metadata URLs enter playback. */
@Composable
internal fun PlaybackSelectionDialog(
    film: Film, episode: Int, line: String, linesOnly: Boolean,
    onSelect: (Int, String) -> Unit, onDismiss: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }
    val selectedFocus = remember(film.id, linesOnly) { FocusRequester() }
    val listState = rememberLazyListState()
    val selectedIndex = if (linesOnly) film.playbackEpisodes.firstOrNull { it.number == episode }?.lines?.indexOfFirst { it.id == line } ?: -1
        else film.playbackEpisodes.indexOfFirst { it.number == episode }
    Dialog(onDismissRequest = onDismiss) {
        Surface {
            LazyColumn(state = listState, modifier = Modifier.width(640.dp).heightIn(max = 430.dp)
                .testTag("playback-selection").padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(if (linesOnly) "切换线路" else "选择集数", fontSize = 24.sp) }
                item { TvButton("返回播放", Modifier.focusRequester(initialFocus)
                    .testTag("playback-selection-close"), onClick = onDismiss) }
                if (linesOnly) {
                    items(film.playbackEpisodes.firstOrNull { it.number == episode }?.lines.orEmpty(), key = { it.id }) { option ->
                        TvButton(option.title, Modifier.testTag("playback-select-line-${option.id}").then(if (option.id == line) Modifier.focusRequester(selectedFocus) else Modifier),
                            selected = line == option.id) { onSelect(episode, option.id) }
                    }
                } else {
                    items(film.playbackEpisodes, key = { it.number }) { entry ->
                        val preferred = entry.lines.firstOrNull { it.id == line } ?: entry.lines.firstOrNull()
                        if (preferred != null) TvButton("第 ${entry.number} 集 · ${entry.title}",
                            Modifier.testTag("playback-select-episode-${entry.number}").then(if (entry.number == episode) Modifier.focusRequester(selectedFocus) else Modifier),
                            selected = episode == entry.number) { onSelect(entry.number, preferred.id) }
                    }
                }
            }
        }
        LaunchedEffect(film.id, linesOnly) {
            if (selectedIndex < 0) initialFocus.requestFocus()
            else {
                listState.scrollToItem(selectedIndex + 2)
                repeat(4) { withFrameNanos { }; if (runCatching { selectedFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
            }
        }
    }
}
