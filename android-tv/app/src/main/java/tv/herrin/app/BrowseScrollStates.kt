package tv.herrin.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*

private val browseCategories = listOf("首页", "电影", "剧集", "动漫", "综艺", "纪录片", "Top 250", "直播", "我的", "search", "recommend")

@Stable
internal class BrowseScrollStates(val rails: Map<String, LazyListState>, val pages: Map<String, ScrollState>)

/** A skippable owner for the saved per-category positions; not rebuilt by every focus animation frame. */
@Composable
internal fun rememberBrowseScrollStates(): BrowseScrollStates {
    val rails = browseCategories.map { category -> key(category) { rememberLazyListState() } }
    val pages = browseCategories.map { category -> key(category) { rememberScrollState() } }
    return remember(rails, pages) { BrowseScrollStates(browseCategories.zip(rails).toMap(), browseCategories.zip(pages).toMap()) }
}
