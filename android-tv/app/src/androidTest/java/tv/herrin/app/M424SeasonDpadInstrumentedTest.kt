package tv.herrin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real Compose-TV focus assertions; no external video backend required. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class M424SeasonDpadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun downFromSeasonTabFocusesFirstLandscapeEpisodeAndOkRoutesSingleClick() {
        val film = Film("authorized-demo", "原创多季剧", "", "剧集", "2026", "", 0,
            playbackEpisodes = listOf(
                PlaybackEpisode(1, "S02E01", listOf(PlaybackLine("s2", "官方", "https://example.test/s2.mp4"))),
                PlaybackEpisode(2, "S03E01", listOf(PlaybackLine("s3", "官方", "https://example.test/s3.mp4"))),
                PlaybackEpisode(3, "S03E02", listOf(PlaybackLine("s3", "官方", "https://example.test/s3-2.mp4")))
            ))
        val layout = SeasonEpisodes.fromFilm(film)!!
        var selected = 2
        var chosen = ""
        compose.setContent {
            var activeSeason by remember { mutableIntStateOf(2) }
            var playbackRequest by remember { mutableStateOf("") }
            val state = androidx.compose.foundation.lazy.rememberLazyListState()
            MaterialTheme {
                Column(Modifier.width(830.dp)) {
                    SeasonBrowser(layout, layout.sections.first { it.number == activeSeason },
                        state, emptyMap(), false, -1, "", history = { null },
                        onSeasonSelect = { activeSeason = it; selected = it },
                        onEpisodeFocus = {},
                        onPlay = { entry ->
                            playbackRequest = "${entry.season}-${entry.number}-${entry.source.number}"
                            chosen = playbackRequest
                        })
                    Text(playbackRequest, modifier = Modifier.fillMaxWidth().testTag("playback-target"))
                }
            }
        }
        compose.onNodeWithTag("season-tab-2").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag("season-tab-2").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("season-tab-3").assertIsFocused()
        compose.waitUntil(3000) { selected == 3 }
        compose.onNodeWithTag("season-tab-3").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(3000) {
            runCatching { compose.onNodeWithTag("season-card-3-0").assertIsFocused() }.isSuccess
        }
        compose.onNodeWithTag("season-card-3-0").assertIsFocused()
        compose.onNodeWithTag("season-card-3-0").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals("3-1-2", chosen)
        compose.onNodeWithTag("playback-target").assertTextEquals("3-1-2")
    }
}
