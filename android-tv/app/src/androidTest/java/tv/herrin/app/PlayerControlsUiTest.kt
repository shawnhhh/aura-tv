package tv.herrin.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI-only tests. They do not claim media decoding or provider authorization. */
class PlayerControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showBar() {
        compose.setContent {
            var playing by remember { mutableStateOf(false) }
            PlayerControlBar(playing, 1f, false, remember { FocusRequester() }, remember { FocusRequester() },
                remember { FocusRequester() }, remember { FocusRequester() }, remember { FocusRequester() },
                Modifier, Modifier, {}, {}, {}, { playing = !playing }, {}, {}, {})
        }
    }

    @Test fun semanticIconsAndSizeHierarchyKeepTransportAtTheScreenCenter() {
        showBar()
        val icons = mapOf("episodes" to "list-video", "rewind" to "rotate-ccw", "toggle" to "play",
            "forward" to "rotate-cw", "audio" to "audio-lines", "subtitle" to "subtitles")
        icons.forEach { (tag, symbol) -> compose.onNodeWithTag("transport-$tag")
            .assert(SemanticsMatcher.expectValue(PlayerControlSymbol, symbol)) }
        val root = compose.onNodeWithTag("player-control-bar").fetchSemanticsNode().boundsInRoot
        val primary = compose.onNodeWithTag("transport-toggle").fetchSemanticsNode().boundsInRoot
        val seek = compose.onNodeWithTag("transport-rewind").fetchSemanticsNode().boundsInRoot
        val side = compose.onNodeWithTag("transport-audio").fetchSemanticsNode().boundsInRoot
        assertTrue(primary.height > seek.height && seek.height > side.height)
        assertEquals(root.center.x.toDouble(), primary.center.x.toDouble(), 1.0)
        compose.onNodeWithTag("transport-audio").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertEquals(seek, compose.onNodeWithTag("transport-rewind").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("transport-toggle").performClick()
        compose.onNodeWithTag("transport-toggle").assert(SemanticsMatcher.expectValue(PlayerControlSymbol, "pause"))
    }

    @Test fun nativeDpadCanReachEveryPermanentAction() {
        showBar()
        compose.onNodeWithTag("transport-toggle").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val inst = InstrumentationRegistry.getInstrumentation()
        for (tag in listOf("rewind", "speed", "episodes")) {
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("transport-$tag").assertIsFocused()
        }
        for (tag in listOf("speed", "rewind", "toggle", "forward", "audio", "subtitle")) {
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("transport-$tag").assertIsFocused()
        }
    }

    @Test fun movieVersionsRetainBothOriginalCoordinatesEvenWithTheSameLineId() {
        val film = Film("versions", "Movie", "", "电影", "2026", "", 1, playbackEpisodes = listOf(
            PlaybackEpisode(2, "影院版", listOf(PlaybackLine("main", "原声", "https://example.invalid/first"))),
            PlaybackEpisode(8, "导演版", listOf(PlaybackLine("main", "原声", "https://example.invalid/second")))))
        var selection: Pair<Int, String>? = null
        compose.setContent { PlaybackSelectionDialog(film, 2, "main", true,
            onSelect = { episode, line -> selection = episode to line }, onDismiss = {}, reduced = true) }
        compose.onNodeWithTag("playback-selection-close").assertDoesNotExist()
        compose.onNodeWithTag("playback-select-line-2-main").assertIsDisplayed()
        compose.onNodeWithTag("playback-select-line-8-main").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(8 to "main", selection) }
    }

    @Test fun emptySelectionRemainsAnOperablePanelWithRemoteBack() {
        val film = Film("empty", "Movie", "", "电影", "2026", "", 1)
        compose.setContent {
            var shown by remember { mutableStateOf(true) }
            if (shown) PlaybackSelectionDialog(film, 1, "missing", true,
                onSelect = { _, _ -> fail("No invented selection") }, onDismiss = { shown = false }, reduced = true)
        }
        compose.onNodeWithTag("playback-selection-empty").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("playback-selection").assertDoesNotExist()
    }

    @Test fun selectedTrackOutsideTheInitialViewportIsScrolledIntoFocus() {
        val options = (0..24).map { PlaybackTransport.Option(it, 0, "受控轨道条目 $it", it == 23) }
        var selected: Pair<Int, Int>? = null
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.height(160.dp)) {
                PlayerControlMenu("音轨", 1f, options, true, {}, { group, track -> selected = group to track })
            }
        }
        compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("player-track-23-0").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("player-track-23-0").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(23 to 0, selected) }
    }
}
