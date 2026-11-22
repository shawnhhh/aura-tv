package tv.herrin.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MinimalPlayerGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun transportIsCenteredSpeedIsVerticalAndMenusNeverReplacePlayer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val media = PlaybackMedia(server.url("/fixture.mp4").toString(), displayTitle = "Geometry fixture")
            val players = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            compose.setContent { AuthorizedPlaybackPanel(media, onPlayer = { players.add(it) }, onBack = {}) }
            compose.waitUntil(7000) { runCatching { compose.onNodeWithTag("transport-toggle").assertExists() }.isSuccess }
            val root = compose.onNodeWithTag("player-root-focus").fetchSemanticsNode().boundsInRoot
            val left = compose.onNodeWithTag("transport-rewind").fetchSemanticsNode().boundsInRoot
            val right = compose.onNodeWithTag("transport-forward").fetchSemanticsNode().boundsInRoot
            assertEquals(root.center.x.toDouble(), ((left.left + right.right) / 2).toDouble(), 2.0)
            compose.onNodeWithTag("transport-episodes").assertIsDisplayed()
            compose.onNodeWithTag("player-back").assertDoesNotExist()
            val primary = compose.onNodeWithTag("transport-toggle").fetchSemanticsNode().boundsInRoot
            val side = compose.onNodeWithTag("transport-audio").fetchSemanticsNode().boundsInRoot
            assertTrue(primary.height > left.height && left.height > side.height)
            compose.onNodeWithTag("kernel-settings").assertDoesNotExist()
            compose.onNodeWithTag("transport-speed").performClick()
            compose.onNodeWithText("2.0×").assertExists()
            val fast = compose.onNodeWithText("2.0×").fetchSemanticsNode().boundsInRoot
            val slow = compose.onNodeWithText("0.5×").fetchSemanticsNode().boundsInRoot
            assertEquals(fast.center.x.toDouble(), slow.center.x.toDouble(), 2.0)
            assertTrue(fast.top < slow.top)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("player-menu-popup").assertDoesNotExist() }.isSuccess }
            compose.onNodeWithTag("transport-subtitle").performClick()
            compose.onNodeWithText("关闭字幕").performClick()
            compose.waitUntil(3000) { runCatching { compose.onNodeWithText("关闭字幕").assertDoesNotExist() }.isSuccess }
            compose.runOnIdle { assertEquals(1, players.size) }
        }
    }

    @Test fun seasonPanelSelectsTheOriginalEpisodeAndLineWithoutInventingCoordinates() {
        val film = Film("fixture", "Fixture", "", "剧集", "2026", "", 1, playbackEpisodes = listOf(
            PlaybackEpisode(7, "S1E1", listOf(PlaybackLine("s1", "第一季", "https://example.invalid/1"))),
            PlaybackEpisode(9, "S2E1", listOf(PlaybackLine("s2", "第二季", "https://example.invalid/2")))))
        var selected: Pair<Int, String>? = null
        compose.setContent { PlaybackSelectionDialog(film, 9, "s2", false,
            onSelect = { ep, line -> selected = ep to line }, onDismiss = {}, reduced = true) }
        compose.onNodeWithTag("playback-select-episode-9").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(9 to "s2", selected) }
        compose.onNodeWithText("第1季").performClick()
        compose.onNodeWithTag("playback-select-episode-7").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(7 to "s1", selected) }
    }

    @Test fun openSubtitlePanelKeepsVideoRunningAndTransportHidesAfterClosing() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bytes = inst.context.assets.open("playback-diagnostics/baseline-72.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(bytes))
            }
            server.start()
            val media = PlaybackMedia(server.url("/baseline.mp4").toString(), displayTitle = "Original controlled fixture")
            val player = java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.ExoPlayer>()
            compose.setContent { AuthorizedPlaybackPanel(media, onPlayer = { player.set(it) }, onBack = {}) }
            var frames = 0
            compose.waitUntil(15000) {
                compose.runOnIdle { frames = player.get()?.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                frames > 0
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("transport-subtitle").performClick()
            val before = frames
            val opened = android.os.SystemClock.elapsedRealtime()
            compose.waitUntil(8000) { android.os.SystemClock.elapsedRealtime() - opened >= 5000 }
            compose.onNodeWithText("关闭字幕").assertIsDisplayed()
            compose.runOnIdle { assertTrue((player.get().videoDecoderCounters?.renderedOutputBufferCount ?: 0) > before) }
            compose.onNodeWithText("关闭字幕").performClick()
            compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("transport-toggle").assertDoesNotExist() }.isSuccess }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess }
        }
    }
}
