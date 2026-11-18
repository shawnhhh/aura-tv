package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** UI over original episode/line objects. No URLs, authorization or player construction. */
@Composable
internal fun PlaybackSelectionDialog(
    film: Film, episode: Int, line: String, linesOnly: Boolean,
    onSelect: (Int, String) -> Unit, onDismiss: () -> Unit, reduced: Boolean = false
) {
    val layout = remember(film) { SeasonEpisodes.fromFilm(film, allowSingleSeason = true) }
    val current = layout?.sections?.firstOrNull { section -> section.episodes.any {
        it.source.number == episode && it.lines.any { option -> option.id == line }
    } }
    var season by remember(film.id) { mutableStateOf(current?.number ?: layout?.sections?.firstOrNull()?.number) }
    var showLines by remember(linesOnly) { mutableStateOf(linesOnly) }
    val selectedFocus = remember(film.id, season, showLines) { FocusRequester() }
    val listState = rememberLazyListState()
    val section = layout?.sections?.firstOrNull { it.number == season }
    val episodes = section?.episodes?.map { it.source } ?: film.playbackEpisodes
    val lines = film.playbackEpisodes.firstOrNull { it.number == episode }?.lines.orEmpty()
    val selectedIndex = if (showLines) lines.indexOfFirst { it.id == line } else episodes.indexOfFirst { it.number == episode }
    var visible by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { visible = true }
    val width = (LocalConfiguration.current.screenWidthDp * .30f).dp
    Popup(alignment = Alignment.CenterStart, properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
        BackHandler(onBack = onDismiss)
        AnimatedVisibility(visible, enter = slideInHorizontally(tween(if (reduced) 0 else 180)) { -it },
            exit = slideOutHorizontally(tween(if (reduced) 0 else 180)) { -it }) {
            Surface(color = Color(0xD9111111)) {
                Column(Modifier.width(width).fillMaxHeight().padding(24.dp).testTag("playback-selection"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (showLines) "线路" else "选集", fontSize = 24.sp, color = Color.White)
                    Row {
                        TvButton("返回播放", Modifier.testTag("playback-selection-close"), reduced = reduced, onClick = onDismiss)
                        TvButton(if (showLines) "选集" else "线路", reduced = reduced) { showLines = !showLines }
                    }
                    if (!showLines && layout != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(layout.sections, key = { it.number }) { entry ->
                            TvButton("第${entry.number}季", selected = season == entry.number, reduced = reduced) { season = entry.number }
                        }
                    }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showLines) items(lines.withIndex().toList(), key = { it.value.id }) { indexed ->
                            val option = indexed.value
                            TvButton("线路 ${indexed.index + 1}", Modifier.testTag("playback-select-line-${option.id}")
                                .then(if (option.id == line || selectedIndex < 0 && indexed.index == 0) Modifier.focusRequester(selectedFocus) else Modifier),
                                selected = line == option.id, reduced = reduced) { onSelect(episode, option.id) }
                        } else items(episodes, key = { it.number }) { entry ->
                            val mapped = section?.episodes?.firstOrNull { it.source.number == entry.number }
                            val preferred = mapped?.preferredLine(line) ?: entry.lines.firstOrNull { it.id == line } ?: entry.lines.firstOrNull()
                            if (preferred != null) TvButton(mapped?.displayTitle ?: entry.title,
                                Modifier.testTag("playback-select-episode-${entry.number}")
                                    .then(if (entry.number == episode || selectedIndex < 0 && entry == episodes.firstOrNull()) Modifier.focusRequester(selectedFocus) else Modifier),
                                selected = episode == entry.number, reduced = reduced) { onSelect(entry.number, preferred.id) }
                        }
                    }
                }
            }
        }
        LaunchedEffect(film.id, season, showLines, visible) {
            if (!visible) return@LaunchedEffect
            listState.scrollToItem(selectedIndex.coerceAtLeast(0))
            repeat(4) { withFrameNanos { }; if (runCatching { selectedFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
        }
    }
}
