package tv.herrin.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * UI-only browser for multiple *source Film* objects. No synthetic Film ID or
 * TMDB metadata may be passed to a playback resolver; actions return original
 * GroupPlaybackRef instances to MainActivity's existing player path.
 */
@Composable
internal fun GroupSeasonBrowser(
    layout: GroupSeasonLayout,
    active: GroupSeasonSection,
    rowState: LazyListState,
    officialEpisodes: Map<Int, TmdbEpisodeDisplay>,
    reduced: Boolean,
    selected: GroupPlaybackRef?,
    focusedEpisode: GroupEpisode?,
    restoreEpisode: Pair<Int, Int>? = null,
    onRestoreFocus: () -> Unit = {},
    history: (GroupEpisode) -> WatchPosition?,
    onSeasonSelect: (Int) -> Unit,
    onEpisodeFocus: (GroupEpisode) -> Unit,
    onEpisodePlay: (GroupEpisode) -> Unit,
    onLinePlay: (GroupPlaybackRef) -> Unit
) {
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    // A requester created by MainActivity would not be attached to these cards.
    // Restore within the composable which owns the actual focus targets.
    LaunchedEffect(active.number, restoreEpisode) {
        if (restoreEpisode?.first != active.number) return@LaunchedEffect
        val index = active.episodes.indexOfFirst { it.number == restoreEpisode.second }
        if (index < 0) {
            onRestoreFocus()
            return@LaunchedEffect
        }
        rowState.scrollToItem(index)
        repeat(4) {
            withFrameNanos { }
            if (runCatching { requester("group-season-card-${active.number}-$index").requestFocus() }
                    .isSuccess) {
                onRestoreFocus()
                return@LaunchedEffect
            }
        }
        onRestoreFocus()
    }
    Column(Modifier.fillMaxWidth().testTag("group-season-browser"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            layout.sections.forEach { section ->
                val tabId = "group-season-tab-${section.number}"
                TvButton("第${section.number}季",
                    modifier = Modifier.testTag(tabId)
                        .focusRequester(requester(tabId))
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                                section.episodes.isNotEmpty()) {
                                onSeasonSelect(section.number)
                                scope.launch {
                                    rowState.scrollToItem(0)
                                    repeat(3) {
                                        withFrameNanos { }
                                        if (runCatching { requester("group-season-card-${section.number}-0").requestFocus() }
                                                .isSuccess) return@launch
                                    }
                                }
                                true
                            } else false
                        },
                    reduced = reduced, selected = section.number == active.number,
                    onFocus = { onSeasonSelect(section.number) }) { onSeasonSelect(section.number) }
            }
        }
        Text("第${active.number}季 · ${active.episodes.size} 集可选",
            modifier = Modifier.testTag("group-season-label"),
            color = Color(0xFFCAD7E6), fontSize = 16.sp)
        if (active.episodes.isEmpty()) {
            Text("当前季正在读取选集，或影视点没有返回可播放的集数。",
                modifier = Modifier.testTag("group-season-empty"),
                color = Color(0xFFBECBD9), fontSize = 14.sp)
        } else {
            LazyRow(Modifier.testTag("group-season-cards"), state = rowState,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(active.episodes,
                    key = { _, entry -> "${entry.season}-${entry.number}" }) { index, entry ->
                    val source = entry.preferred(selected)
                    val card = SeasonEpisode(entry.season, entry.number,
                        source.source, listOf(source.line))
                    val isSelected = selected?.let {
                        it.film.id == source.film.id && it.source.number == source.source.number &&
                            it.line.id == source.line.id
                    } ?: false
                    SeasonEpisodeCard(card, officialEpisodes[entry.number], history(entry),
                        selected = isSelected, reduced = reduced,
                        modifier = Modifier.testTag("group-season-card-${entry.season}-$index")
                            .focusRequester(requester("group-season-card-${entry.season}-$index"))
                            .focusProperties { up = requester("group-season-tab-${entry.season}") },
                        onFocus = { onEpisodeFocus(entry) },
                        onClick = { onEpisodePlay(entry) })
                }
            }
            val alternatives = focusedEpisode?.takeIf { it.season == active.number }
                ?: active.episodes.firstOrNull()
            if (alternatives != null && alternatives.sources.size > 1) {
                Text("播放来源 · ${alternatives.sources.size} 条线路",
                    color = Color(0xFFBECBD9), fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    alternatives.sources.forEachIndexed { index, ref ->
                        TvButton("${ref.film.title.take(22)} · ${ref.line.title.take(16)}",
                            modifier = Modifier.testTag("group-source-line-${alternatives.season}-${alternatives.number}-$index"),
                            reduced = reduced, quiet = true,
                            selected = selected?.let { it.film.id == ref.film.id &&
                                it.source.number == ref.source.number && it.line.id == ref.line.id } == true) {
                            onLinePlay(ref)
                        }
                    }
                }
            }
        }
    }
}

/** Fallback for an unnumbered provider item; it intentionally returns a REAL Film. */
@Composable
internal fun GroupRawSourceChooser(
    members: List<ShowMember>,
    reduced: Boolean,
    onChoose: (Film) -> Unit
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        members.forEach { member ->
            TvButton("查看原始条目 · ${member.film.title.take(22)}",
                modifier = Modifier.testTag("group-raw-source-${member.film.id}"),
                reduced = reduced, quiet = true) { onChoose(member.film) }
        }
    }
}
