package tv.herrin.app

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Player
import com.fongmi.android.tv.bean.Track
import com.fongmi.android.tv.player.media.PlaySpec
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FongMiPlayerManagerIntegrationTest {
    @Test fun mediaItemUsesExplicitSubtitleMimeAndSourceFlags() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val subtitles = listOf(
                AuthorizedSubtitle("en", "SRT", "https://original.invalid/a.srt", C.SELECTION_FLAG_FORCED),
                AuthorizedSubtitle("zh", "ASS", "https://original.invalid/a.ass"),
                AuthorizedSubtitle("en", "TTML", "https://original.invalid/caption", 0, "application/ttml+xml"))
            val adapter = HerrinPlaybackAdapter(inst.targetContext,
                PlaybackMedia("https://original.invalid/never-requested.mp4", subtitles = subtitles))
            try {
                val configs = adapter.item().localConfiguration!!.subtitleConfigurations
                assertEquals(listOf("application/x-subrip", "text/x-ssa", "application/ttml+xml"), configs.map { it.mimeType })
                assertEquals(listOf(C.SELECTION_FLAG_FORCED, C.SELECTION_FLAG_AUTOSELECT,
                    C.SELECTION_FLAG_AUTOSELECT), configs.map { it.selectionFlags })
                assertTrue(configs.all { it.id!!.startsWith("external:") })
            } finally { adapter.close() }
        }
    }

    @Test fun productionAdapterOwnsMediaIdentityHeadersControlsAndRelease() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fixture = File(inst.targetContext.cacheDir, "manager-original-${java.util.UUID.randomUUID()}.mp4")
        inst.context.assets.open("synthetic.mp4").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            inst.runOnMainSync {
                val adapter = HerrinPlaybackAdapter(inst.targetContext, PlaybackMedia(
                    Uri.fromFile(fixture).toString(), mapOf("X-Fixture" to "original"),
                    declaredMime = "video/mp4", mediaId = "original-film/episode-1/main"))
                val player = adapter.player
                try {
                    adapter.start()
                    val item = player.currentMediaItem!!
                    assertEquals("original-film/episode-1/main", item.mediaId)
                    assertEquals("original", item.requestMetadata.extras!!.getString("X-Fixture"))
                    assertEquals("video/mp4", item.localConfiguration!!.mimeType)
                    adapter.pause()
                    assertFalse(player.playWhenReady)
                    adapter.play()
                    assertTrue(player.playWhenReady)
                    adapter.seekTo(500)
                    assertEquals(500L, player.currentPosition)
                    adapter.start(position = 250)
                    assertEquals(250L, player.currentPosition)
                    assertEquals(2L, adapter.manager.playbackAttempt)
                    assertEquals(5f, adapter.manager.setSpeed(9f))
                    assertEquals(0.1f, adapter.manager.setSpeed(-1f))
                    adapter.manager.setSpeed(1.25f)
                    assertTrue(adapter.manager.startSpeedPress(2f))
                    assertTrue(adapter.manager.startSpeedPress(3f))
                    adapter.manager.endSpeedPress()
                    assertEquals(1.25f, player.playbackParameters.speed)
                    adapter.manager.endSpeedPress()
                    assertEquals(1.25f, player.playbackParameters.speed)
                    assertThrows(IllegalArgumentException::class.java) { adapter.manager.setSpeed(Float.NaN) }
                    adapter.manager.setRepeatOne(true)
                    assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
                    adapter.manager.setRepeatOne(false)
                    assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
                    adapter.manager.setSkipSilenceEnabled(true)
                    assertTrue(player.skipSilenceEnabled)
                    adapter.manager.setSkipSilenceEnabled(false)
                    assertFalse(player.skipSilenceEnabled)
                    adapter.manager.replay(125L)
                    assertEquals(125L, player.currentPosition)
                    assertTrue(player.playWhenReady)
                } finally { adapter.close(); adapter.close() }
                assertTrue(player.isReleased)
                assertThrows(IllegalStateException::class.java) { adapter.play() }
            }
        } finally { fixture.delete() }
    }

    @Test fun actualAudioChoiceAndSubtitleOffSurviveRetryButNotAnotherMediaIdentity() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val movie = File(inst.targetContext.cacheDir, "manager-tracks.mp4")
        val caption = File(inst.targetContext.cacheDir, "manager-tracks.vtt")
        inst.context.assets.open("synthetic.mp4").use { input -> movie.outputStream().use { input.copyTo(it) } }
        inst.context.assets.open("synthetic.vtt").use { input -> caption.outputStream().use { input.copyTo(it) } }
        val signals = LinkedBlockingQueue<Unit>()
        lateinit var adapter: HerrinPlaybackAdapter
        var created: HerrinPlaybackAdapter? = null
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (true) {
                var ready = false
                inst.runOnMainSync { ready = predicate() }
                if (ready) return
                val left = deadline - System.nanoTime()
                assertTrue(message, left > 0 && signals.poll(left, TimeUnit.NANOSECONDS) != null)
            }
        }
        try {
            inst.runOnMainSync {
                adapter = HerrinPlaybackAdapter(inst.targetContext, PlaybackMedia(
                    Uri.fromFile(movie).toString(), mapOf("X-Fixture" to "private-fixture-value"),
                    listOf(AuthorizedSubtitle("en", "English", Uri.fromFile(caption).toString())),
                    mediaId = "private-media-identity"))
                created = adapter
                adapter.player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { signals.offer(Unit) }
                })
                adapter.start()
                adapter.pause()
            }
            await("Real dual audio and external subtitle tracks must load") {
                PlaybackTransport.options(adapter.player, C.TRACK_TYPE_AUDIO).size >= 2 &&
                    PlaybackTransport.options(adapter.player, C.TRACK_TYPE_TEXT).isNotEmpty()
            }
            var original = ""
            var chosen = ""
            inst.runOnMainSync {
                original = PlaybackTransport.currentLabel(adapter.player, C.TRACK_TYPE_AUDIO)
                assertTrue(adapter.manager.cycleTrack(C.TRACK_TYPE_AUDIO))
                assertTrue(adapter.manager.setTrack(Track(C.TRACK_TYPE_TEXT, "", "").apply { isSelected = false }))
            }
            await("Selected audio must actually change") {
                chosen = PlaybackTransport.currentLabel(adapter.player, C.TRACK_TYPE_AUDIO)
                chosen != "默认" && chosen != original
            }
            inst.runOnMainSync { adapter.start(position = 300); adapter.pause() }
            await("Retry must restore chosen decoded audio and subtitle OFF") {
                adapter.player.playbackState == Player.STATE_READY &&
                    PlaybackTransport.currentLabel(adapter.player, C.TRACK_TYPE_AUDIO) == chosen &&
                    C.TRACK_TYPE_TEXT in adapter.player.trackSelectionParameters.disabledTrackTypes
            }
            inst.runOnMainSync {
                assertEquals(300L, adapter.player.currentPosition)
                adapter.manager.start(PlaySpec("other-episode", adapter.media))
                adapter.pause()
                assertTrue(adapter.player.trackSelectionParameters.overrides.isEmpty())
                assertFalse(C.TRACK_TYPE_TEXT in adapter.player.trackSelectionParameters.disabledTrackTypes)
                val safe = HerrinPlaybackDiagnosticJournal.export()
                assertTrue(safe.contains("PLAYBACK_INITIALIZING"))
                assertTrue(safe.contains("TRACKS_CHANGED"))
                assertFalse(safe.contains("private-fixture-value"))
                assertFalse(safe.contains("private-media-identity"))
                assertFalse(safe.contains(movie.toString()))
            }
        } finally {
            created?.let { inst.runOnMainSync { it.close() } }
            movie.delete(); caption.delete()
        }
    }
}
