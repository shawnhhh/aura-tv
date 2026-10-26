package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Media3 decoded tracks and OS remote key events, not synthetic button callbacks. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackTransportTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun until(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 18_000
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(90)
        }
        fail(message)
    }

    @Test fun realRemoteSeekAndDecodedAudioSubtitleTracks() {
        val context = inst.targetContext
        val movie = File(context.cacheDir, "m23-dual-audio.mp4")
        inst.context.assets.open("synthetic.mp4").use { input -> movie.outputStream().use { input.copyTo(it) } }
        val caption = File(context.cacheDir, "m23-caption.vtt")
        inst.context.assets.open("synthetic.vtt").use { input -> caption.outputStream().use { input.copyTo(it) } }
        val intent = Intent(context, PlaybackTestActivity::class.java)
            .putExtra("fixture", movie.toURI().toString())
            .putExtra("subtitle", caption.toURI().toString())
        ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
            until("Real video decoder must render before controlling tracks") {
                var rendered = 0
                scenario.onActivity {
                    rendered = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                }
                rendered > 3
            }
            var original = ""
            until("FFmpeg original must expose two supported AAC language tracks") {
                var count = 0
                scenario.onActivity {
                    val tracks = PlaybackTransport.options(it.player, C.TRACK_TYPE_AUDIO)
                    count = tracks.size
                    original = tracks.firstOrNull { t -> t.selected }?.label.orEmpty()
                }
                count >= 2 && original.isNotEmpty()
            }
            var initialAudio: String? = null
            scenario.onActivity {
                initialAudio = PlaybackTransport.options(it.player, C.TRACK_TYPE_AUDIO)
                    .firstOrNull { t -> t.selected }?.label
                assertTrue("Switch to second *decoded* audio track",
                    PlaybackTransport.cycle(it.player, C.TRACK_TYPE_AUDIO))
            }
            until("Media3 selected audio track must change") {
                var chosen: String? = null
                scenario.onActivity {
                    chosen = PlaybackTransport.options(it.player, C.TRACK_TYPE_AUDIO)
                        .firstOrNull { t -> t.selected }?.label
                }
                chosen != null && chosen != initialAudio
            }
            until("External WebVTT must be exposed as actual Media3 subtitle track") {
                var available = false
                scenario.onActivity {
                    available = PlaybackTransport.options(it.player, C.TRACK_TYPE_TEXT).isNotEmpty()
                }
                available
            }
            var subtitlesBefore = false
            scenario.onActivity {
                subtitlesBefore = PlaybackTransport.options(it.player, C.TRACK_TYPE_TEXT).any { t -> t.selected }
                assertTrue("External WebVTT toggle must be actionable",
                    PlaybackTransport.cycle(it.player, C.TRACK_TYPE_TEXT))
            }
            until("Media3 subtitle selection must actually toggle") {
                var subtitlesAfter = false
                scenario.onActivity {
                    subtitlesAfter = PlaybackTransport.options(it.player, C.TRACK_TYPE_TEXT).any { t -> t.selected }
                }
                subtitlesBefore != subtitlesAfter
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            until("Real remote PAUSE stops player") {
                var stopped = false
                scenario.onActivity { stopped = !it.player.playWhenReady }
                stopped
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            until("Real remote fast-forward must seek to duration boundary") {
                var reached = false
                scenario.onActivity {
                    reached = it.player.duration > 0 && it.player.currentPosition >= it.player.duration - 500L
                }
                reached
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_REWIND)
            until("Real remote rewind must seek near beginning") {
                var nearStart = false
                scenario.onActivity { nearStart = it.player.currentPosition <= 500L }
                nearStart
            }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            until("Back must release player after audio/subtitle/seek changes") {
                var released = false
                scenario.onActivity { released = it.player.isReleased }
                released
            }
        }
    }
}
