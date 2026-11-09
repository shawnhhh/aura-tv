package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

internal object TmdbBrowseFocus {
    fun tag(page: TmdbBrowsePage, key: String, id: String, recent: Boolean): String = when {
        key == "recent" -> "card-$id"
        key == TmdbRailSpec(page, TmdbRailKind.HOT).key -> (if (page == TmdbBrowsePage.HOME && recent) "recommend-" else "card-") + id
        else -> "tmdb-$key-$id"
    }
    fun first(state: TmdbBrowseState, page: TmdbBrowsePage, recent: List<Film>): String? {
        if (page == TmdbBrowsePage.HOME && recent.isNotEmpty()) return "card-" + recent.first().id
        return TmdbRailSpec.forPage(page).firstNotNullOfOrNull { s ->
            state.row(s).entries.firstOrNull()?.let { tag(page, s.key, it.film.id, false) }
        }
    }
}

/** Reuses the existing poster, TV buttons and outer Hero/navigation; rows own data and scroll state. */
@Composable
internal fun TmdbBrowseRails(
    state: TmdbBrowseState, page: TmdbBrowsePage, recent: List<Film>, reduced: Boolean,
    requester: (String) -> FocusRequester, onFocus: (Film, String) -> Unit, onOpen: (Film) -> Unit, restoreFocus: String? = null
) {
    val scope = rememberCoroutineScope()
    val specs = TmdbRailSpec.forPage(page)
    val lists = buildList<Pair<String, List<Film>>> {
        if (page == TmdbBrowsePage.HOME) add("recent" to recent)
        specs.forEach { s -> add(s.key to state.row(s).entries.map { it.film }) }
    }
    val hasRecent = page == TmdbBrowsePage.HOME && recent.isNotEmpty()
    Column(Modifier.fillMaxWidth().testTag("tmdb-page-" + page.name.lowercase()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lists.forEachIndexed { rowIndex, (key, films) ->
            androidx.compose.runtime.key(key) {
                val spec = specs.firstOrNull { it.key == key }
                val status = spec?.let(state::row)
                val rail = state.scrolls.getOrPut(key) { LazyListState() }
                var focusedPoster by remember { mutableStateOf<String?>(null) }
                Text(spec?.title ?: "最近播放", color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.testTag("tmdb-heading-$key"))
                if (films.isEmpty()) Text(when {
                    key == "recent" -> "暂无最近播放"
                    status?.loading == true -> "正在加载…"
                    status?.error == true -> "加载失败，保留已有缓存"
                    else -> "暂无可确认已发行或已播出的内容"
                }, color = Color(0xFFB8BEC8), fontSize = 14.sp)
                if ((status?.unknown ?: 0) > 0) Text("发行或播出信息未知：" + status!!.unknown + " 部，暂未推荐",
                    color = Color(0xFFB8BEC8), fontSize = 12.sp, modifier = Modifier.testTag("tmdb-unknown-$key"))
                LazyRow(state = rail, modifier = Modifier.testTag("tmdb-rail-$key"),
                    contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(films, key = { _, f -> f.id }) { index, film ->
                        val tag = TmdbBrowseFocus.tag(page, key, film.id, hasRecent)
                        LaunchedEffect(tag) {
                            if (tag == restoreFocus) {
                                withFrameNanos { }
                                runCatching { requester(tag).requestFocus() }
                            }
                        }
                        Poster(film, Modifier.testTag(tag).focusRequester(requester(tag))
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                                    val up = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                                    val targetIndex = if (up) (rowIndex - 1 downTo 0).firstOrNull { lists[it].second.isNotEmpty() }
                                        else (rowIndex + 1 until lists.size).firstOrNull { lists[it].second.isNotEmpty() }
                                    scope.launch {
                                        if (targetIndex == null) { if (up) requester("play").requestFocus() }
                                        else {
                                            val (targetKey, targetFilms) = lists[targetIndex]
                                            val n = index.coerceAtMost(targetFilms.lastIndex)
                                            state.scrolls.getOrPut(targetKey) { LazyListState() }.scrollToItem(n)
                                            withFrameNanos { }
                                            runCatching { requester(TmdbBrowseFocus.tag(page, targetKey, targetFilms[n].id, hasRecent)).requestFocus() }
                                        }
                                    }
                                    true
                                } else false
                            }, reduced, onFocus = { focusedPoster = film.id; state.focus(key); onFocus(film, tag) }, onClick = { onOpen(film) })
                    }
                    if (spec != null && status != null && (status.error || status.unknown > 0)) item(key = "retry") {
                        TvButton("重试此栏目", Modifier.testTag("tmdb-retry-$key"), reduced = reduced, onFocus = { focusedPoster = null }) {
                            if (!status.loading) scope.launch { state.retry(spec) }
                        }
                    }
                    if (spec != null && status != null && status.hasMore) item(key = "next") {
                        TvButton(if (status.loading) "加载中…" else if (status.error) "重试此栏目" else "加载更多",
                            Modifier.testTag("tmdb-more-$key"), reduced = reduced, onFocus = { focusedPoster = null }) {
                            if (!status.loading) scope.launch { if (status.error) state.retry(spec) else state.more(spec) }
                        }
                    }
                }
                if (spec != null) LaunchedEffect(key, films.size, status?.hasMore, status?.loading) {
                    snapshotFlow { (rail.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to focusedPoster }.distinctUntilChanged().collect { (last, focused) ->
                        if (films.size >= 6 && focused != null && films.takeLast(2).any { it.id == focused } && last >= films.size - 2 && status?.hasMore == true && !status.loading && !status.error)
                            state.more(spec)
                    }
                }
            }
        }
    }
}
