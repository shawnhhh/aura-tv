package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    onSelect: (Int, String) -> Unit, onDismiss: () -> Unit, reduced: Boolean = false,
    onBack: (Long?) -> Unit = { onDismiss() }
) {
    val movie = film.category == "电影"
    val layout = remember(film) { SeasonEpisodes.fromFilm(film, allowSingleSeason = true) }
    val current = layout?.sections?.firstOrNull { section -> section.episodes.any {
        it.source.number == episode && it.lines.any { option -> option.id == line }
    } }
    var season by remember(film.id) { mutableStateOf(current?.number) }
    var showLines by remember(linesOnly) { mutableStateOf(linesOnly) }
    val selectedFocus = remember(film.id, film.playbackEpisodes, season, showLines) { FocusRequester() }
    val listState = rememberLazyListState()
    val section = layout?.sections?.firstOrNull { it.number == season }
    val episodes = section?.episodes?.map { it.source } ?: film.playbackEpisodes
    val lineChoices = if (movie) film.playbackEpisodes.flatMap { entry ->
        entry.lines.map { PlaybackLineChoice(entry.number, it, listOf(entry.title, it.title).filter(String::isNotBlank).distinct().joinToString(" · ")) }
    } else film.playbackEpisodes.firstOrNull { it.number == episode }?.lines.orEmpty().map {
        PlaybackLineChoice(episode, it, it.title)
    }
    val episodeChoices = episodes.mapNotNull { entry ->
        val mapped = section?.episodes?.firstOrNull { it.source.number == entry.number }
        val preferred = mapped?.preferredLine(line) ?: entry.lines.firstOrNull { it.id == line } ?: entry.lines.firstOrNull()
        preferred?.let { PlaybackLineChoice(entry.number, it, mapped?.displayTitle ?: entry.title) }
    }
    val choices = if (showLines || movie) lineChoices else episodeChoices
    val selectedIndex = choices.indexOfFirst { it.episode == episode && (!showLines && !movie || it.line.id == line) }
    var focusAttached by remember(film.id, film.playbackEpisodes, season, showLines) { mutableStateOf(false) }
    var visible by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { visible = true }
    val width = (LocalConfiguration.current.screenWidthDp * .30f).dp
    Popup(alignment = Alignment.CenterStart, properties = PopupProperties(focusable = true, dismissOnBackPress = false), onDismissRequest = onDismiss) {
        BackHandler { onBack(null) }
        AnimatedVisibility(visible, enter = slideInHorizontally(tween(if (reduced) 0 else 180)) { -it },
            exit = slideOutHorizontally(tween(if (reduced) 0 else 180)) { -it }) {
            Surface(modifier = Modifier.onPlaybackPopupBack(onBack), color = Color(0xD9111111)) {
                Column(Modifier.width(width).fillMaxHeight().padding(24.dp).testTag("playback-selection"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (movie) "版本与线路" else if (showLines) "线路" else "选集", fontSize = 24.sp, color = Color.White)
                    if (!movie) TvButton(if (showLines) "选集" else "线路",
                        Modifier.testTag("playback-selection-mode"), reduced = reduced) { showLines = !showLines }
                    if (!movie && !showLines && layout != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(layout.sections, key = { it.number }) { entry ->
                            TvButton("第${entry.number}季", selected = season == entry.number, reduced = reduced) { season = entry.number }
                        }
                    }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (choices.isEmpty()) item {
                            Box(Modifier.focusRequester(selectedFocus).focusable().onGloballyPositioned { focusAttached = true }
                                .testTag("playback-selection-empty").padding(12.dp)) {
                                Text("暂无可用版本或集数", color = Color.White)
                            }
                        }
                        items(choices.withIndex().toList(), key = { "${it.value.episode}:${it.value.line.id}" }) { indexed ->
                            val choice = indexed.value
                            val focusTarget = indexed.index == selectedIndex.coerceAtLeast(0)
                            val tag = if (showLines || movie) "playback-select-line-${choice.episode}-${choice.line.id}"
                                else "playback-select-episode-${choice.episode}"
                            TvButton(choice.label.ifBlank { "当前版本" }, Modifier.testTag(tag)
                                .then(if (focusTarget) Modifier.focusRequester(selectedFocus)
                                    .onGloballyPositioned { focusAttached = true } else Modifier),
                                selected = selectedIndex == indexed.index, reduced = reduced) { onSelect(choice.episode, choice.line.id) }
                        }
                    }
                }
            }
        }
        LaunchedEffect(film.id, choices, selectedIndex, season, showLines, visible) {
            if (visible && choices.isNotEmpty()) listState.scrollToItem(selectedIndex.coerceAtLeast(0))
        }
        LaunchedEffect(selectedFocus, focusAttached, visible) {
            if (visible && focusAttached) {
                withFrameNanos { }
                selectedFocus.requestFocus()
            }
        }
    }
}

private data class PlaybackLineChoice(val episode: Int, val line: PlaybackLine, val label: String)


internal fun Modifier.onPlaybackPopupBack(onBack: (Long) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val key = event.nativeKeyEvent
    if (key.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
    if (key.action == android.view.KeyEvent.ACTION_UP && !key.isCanceled) onBack(key.downTime)
    true
}
