package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the same panel as production using CI-generated synthetic H.264/AAC, never a film. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class MediaDecoderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        fail(message)
    }
    private fun capture(name: String) {
        fun shell(command: String) {
            inst.uiAutomation.executeShellCommand(command).use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
        }
        shell("mkdir -p /data/local/tmp/herrin-test-evidence")
        shell("screencap -p /data/local/tmp/herrin-test-evidence/$name.png")
    }
    @Test fun invalidMediaShowsErrorAndBackReleases() {
        val file = File(inst.targetContext.cacheDir, "invalid.mp4").apply { writeText("synthetic invalid format") }
        val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java).putExtra("fixture", file.toURI().toString())
        ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
            await("Invalid media must raise a playback error") {
                var failed = false
                scenario.onActivity { failed = it.player.playerError != null }
                failed
            }
            capture("player-format-error")
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Error screen Back must release") {
                var released = false
                scenario.onActivity { released = it.player.isReleased }
                released
            }
        }
    }
    @Test fun actualFramesRemotePauseBackgroundBackAndRepeatRelease() {
        val context = inst.targetContext
        val fixture = File(context.cacheDir, "synthetic.mp4")
        inst.context.assets.open("synthetic.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        repeat(2) { cycle ->
            val states = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val intent = Intent(context, PlaybackTestActivity::class.java).putExtra("fixture", fixture.toURI().toString())
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                // Poll actual decoder counters on the application thread; READY alone is insufficient.
                var rendered = 0
                var initialized = 0
                var playerError: String? = null
                await("H.264 decoder must render frames") {
                    scenario.onActivity { activity ->
                        val p = activity.player
                        states.clear(); states.addAll(activity.states)
                        rendered = p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        initialized = p.videoDecoderCounters?.decoderInitCount ?: 0
                        playerError = p.playerError?.errorCodeName
                    }
                    playerError?.let { fail(it) }
                    rendered > 3 && initialized > 0
                }
                assertTrue(states.contains(Player.STATE_BUFFERING))
                assertTrue(states.contains(Player.STATE_READY))
                android.util.Log.i("HerrinDecoder", "cycle=$cycle states=$states decoderInit=$initialized rendered=$rendered")
                capture("decoded-frames-$cycle")
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                scenario.onActivity { assertFalse(it.player.playWhenReady) }
                var pausedAt = 0L
                scenario.onActivity { pausedAt = it.player.currentPosition }
                Thread.sleep(250)
                scenario.onActivity { assertTrue(kotlin.math.abs(it.player.currentPosition - pausedAt) < 100) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                scenario.onActivity { assertTrue(it.player.playWhenReady) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                capture("player-remote-focus-$cycle")
                // Activity STOP is the lifecycle transition caused by Home/background.
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { assertFalse(it.player.playWhenReady) }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                await("Synthetic clip must reach ENDED") {
                    var ended = false
                    scenario.onActivity { ended = it.player.playbackState == Player.STATE_ENDED }
                    ended
                }
                android.util.Log.i("HerrinDecoder", "cycle=$cycle reached ENDED")
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back must release the player") {
                    var released = false
                    scenario.onActivity { released = it.player.isReleased }
                    released
                }
            }
        }
    }
}
