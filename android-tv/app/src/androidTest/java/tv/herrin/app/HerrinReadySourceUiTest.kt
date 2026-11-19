package tv.herrin.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TV D-pad regression for a fully prefetched multi-line source. */
@OptIn(ExperimentalTestApi::class)
class HerrinReadySourceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nodeAppearsOnlyWhenLineAndEpisodeLayoutsReadyAndSecondLineDownEntersEpisodes() {
        val movie = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            Film("cms-film", "Original", "", "剧集", "2026", "", 1, cmsVodId = "a"), "tv")
        val source = HerrinSourceDiscovery.Node("cms", "已授权节点",
            HerrinSourceType.TVBOX_CMS, { listOf(movie) }, { null })
        val hit = HerrinSourceDiscovery.Hit("cms:a", source, movie,
            episodes = listOf(HerrinSourceDiscovery.EpisodeLayout(1, "第一集",
                listOf("primary" to "主线路", "backup" to "备用线路"))))
        val waiting = HerrinSourceDiscovery.Snapshot(
            "r", 1, hits = listOf(hit), routes = listOf(
                HerrinSourceDiscovery.Route("cms", "cms:a", "primary"),
                HerrinSourceDiscovery.Route("cms", "cms:a", "backup")))
        var snapshot by mutableStateOf(waiting)
        var currentNode by mutableStateOf<String?>(null)
        var focusedLine = -1
        var selectedLine: String? = null
        val requesters = mutableMapOf<Int, FocusRequester>()
        val episodeFocus = FocusRequester()
        compose.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                LazyColumn(state = list) {
                    item { Text("剧情简介", modifier = Modifier.testTag("synopsis-heading")) }
                    item { TvButton("♡ 添加收藏",
                        Modifier.testTag("detail-favorite"), reduced = true) {} }
                    herrinDiscoveryItems(
                        snapshot, running = snapshot.detailed.isEmpty(), reduced = true,
                        select = { _, line -> selectedLine = line },
                        resume = {}, selectedNode = currentNode,
                        onNode = { currentNode = it },
                        lineFocus = { index ->
                            requesters.getOrPut(index) { FocusRequester() }
                        },
                        onLineFocus = { focusedLine = it },
                        onDown = { scope.launch {
                            DetailRegionFocus.move(list, episodeFocus, true)
                        } }
                    )
                    item {
                        TvButton("第1集", Modifier.testTag("episode-1")
                            .focusRequester(episodeFocus), reduced = true) {}
                    }
                }
            }
        }
        compose.onNodeWithTag("source-node-menu").assertDoesNotExist()
        compose.onNodeWithTag("source-node-wait").assertExists()
        compose.onNodeWithTag("source-spinner").assertExists()
        compose.runOnIdle { snapshot = waiting.copy(detailed = setOf("cms:a")) }
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("source-node-menu").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("source-node-wait").assertDoesNotExist()
        compose.onNodeWithTag("source-line-cms:a-backup").assertExists()
        compose.onNodeWithTag("source-line-cms:a-backup")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("source-line-cms:a-backup").assertIsFocused()
        assertEquals(1, focusedLine)
        compose.onNodeWithTag("source-line-cms:a-backup").performClick()
        assertEquals("backup", selectedLine)
        compose.onNodeWithTag("source-line-cms:a-backup")
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(5000) { runCatching {
            compose.onNodeWithTag("episode-1").assertIsFocused()
        }.isSuccess }
    }
}
