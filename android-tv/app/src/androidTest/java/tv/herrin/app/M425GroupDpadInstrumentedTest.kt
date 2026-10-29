package tv.herrin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class M425GroupDpadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun film(id: String, title: String, season: Int?) = Film(
        id, title, "", "动漫", "2026", "", 0,
        playbackEpisodes = listOf(PlaybackEpisode(1, if (season == null) "第1集" else "01",
            listOf(PlaybackLine("line-$id", "本地测试", "https://example.test/$id.mp4")))),
        cmsVodId = id)

    @Test fun downFromSeasonTwoTabToLandscapeCardAndEnterRetainsOriginalFilmAndLine() {
        val a = film("original-s1", "原创剧 第一季", 1)
        val b = film("original-s2", "原创剧 第二季", 2)
        val layout = GroupSeasonIndex.from(ShowCatalog.build(listOf(a, b), "site:one").groups.single())
        var played: PlaybackRequest? = null
        compose.setContent {
            var active by remember { mutableIntStateOf(1) }
            val list = rememberLazyListState()
            MaterialTheme {
                Column(Modifier.width(830.dp)) {
                    GroupSeasonBrowser(layout, layout.sections.single { it.number == active },
                        list, emptyMap(), reduced = false, selected = null, focusedEpisode = null,
                        history = { null }, onSeasonSelect = { active = it },
                        onEpisodeFocus = {}, onEpisodePlay = { played = it.sources.single().request() },
                        onLinePlay = { played = it.request() })
                }
            }
        }
        compose.onNodeWithTag("group-season-tab-1").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("group-season-tab-1").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("group-season-tab-2").assertIsFocused()
        compose.onNodeWithTag("group-season-tab-2").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(3_000) {
            runCatching { compose.onNodeWithTag("group-season-card-2-0").assertIsFocused() }.isSuccess
        }
        compose.onNodeWithTag("group-season-card-2-0").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(PlaybackRequest("original-s2", 1, "line-original-s2"), played)
    }

    @Test fun rawSourceFallbackOpensOriginalFilmSelectionWhenSeasonCannotBeMapped() {
        val marked = film("original-s1", "原创剧 第一季", 1).copy(tmdbTvId = 344)
        val unlabeled = film("original-raw", "原创剧", null).copy(tmdbTvId = 344)
        val catalog = ShowCatalog.build(listOf(marked, unlabeled), "site:one")
        val group = catalog.groups.single()
        val layout = GroupSeasonIndex.from(group)
        val unmapped = group.members.filter { member ->
            layout.sections.none { section -> section.episodes.any { episode ->
                episode.sources.any { it.film.id == member.film.id }
            } }
        }
        assertEquals(listOf("original-raw"), unmapped.map { it.film.id })
        var entered: PlaybackRequest? = null
        compose.setContent {
            var bypass by remember { mutableStateOf<String?>(null) }
            var selected by remember { mutableStateOf(marked) }
            val displayedGroup = catalog.groupFor(selected.id).takeUnless { bypass == selected.id }
            MaterialTheme {
                Column(Modifier.width(830.dp)) {
                    if (displayedGroup != null) GroupRawSourceChooser(unmapped, false) {
                        bypass = it.id
                        selected = it
                    } else {
                        Text("原始选集", modifier = Modifier.testTag("original-picker"))
                        selected.playbackEpisodes.forEach { original ->
                            TvButton("第${original.number}集", Modifier.testTag("raw-episode-${original.number}")) {
                                entered = PlaybackRequest(selected.id, original.number, original.lines.first().id)
                            }
                        }
                    }
                }
            }
        }
        assertNull(entered)
        compose.onNodeWithTag("group-raw-source-original-raw").performClick()
        compose.onNodeWithTag("original-picker").assertExists()
        compose.onNodeWithTag("raw-episode-1").performClick()
        assertEquals(PlaybackRequest("original-raw", 1, "line-original-raw"), entered)
    }
}
