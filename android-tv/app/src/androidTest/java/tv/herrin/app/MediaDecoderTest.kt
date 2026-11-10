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

/** Exercises production panel with bundled original test-only H.264/AAC, never a film. */
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
                await("Remote pause must update playWhenReady") {
                    var paused = false
                    scenario.onActivity { paused = !it.player.playWhenReady }
                    paused
                }
                // playWhenReady is masked on the application thread before the internal
                // playback thread acknowledges pause. A no-op PlayerMessage crosses the
                // playback queue and returns to the application looper before sampling.
                // This does not pause/seek again or change any player state.
                lateinit var pauseAcknowledged: androidx.media3.exoplayer.PlayerMessage
                scenario.onActivity {
                    pauseAcknowledged = it.player.createMessage { _, _ -> }
                        .setLooper(it.player.applicationLooper).send()
                }
                assertTrue("Playback thread must acknowledge the remote pause",
                    pauseAcknowledged.blockUntilDelivered(5_000L))
                // Require two successive windows with <100ms TOTAL drift. A single
                // quiet window can precede a delayed audio timestamp reconciliation.
                await("Remote pause must settle the media clock") {
                    var first = 0L
                    var second = 0L
                    var third = 0L
                    var paused = false
                    scenario.onActivity {
                        paused = !it.player.playWhenReady
                        first = it.player.currentPosition
                    }
                    Thread.sleep(220)
                    scenario.onActivity { second = it.player.currentPosition }
                    Thread.sleep(220)
                    scenario.onActivity {
                        third = it.player.currentPosition
                        paused = paused && !it.player.playWhenReady && !it.player.isPlaying
                    }
                    android.util.Log.i("HerrinDecoder", "pause cycle=$cycle first=$first second=$second third=$third paused=$paused")
                    paused && kotlin.math.abs(second - first) < 100L && kotlin.math.abs(third - first) < 100L
                }
                var pausedAt = 0L
                scenario.onActivity { pausedAt = it.player.currentPosition }
                Thread.sleep(250)
                scenario.onActivity {
                    assertFalse("Playback must still be paused", it.player.playWhenReady)
                    val sampled = it.player.currentPosition
                    assertTrue("Paused media clock advanced after settling: cycle=$cycle before=$pausedAt after=$sampled state=${it.player.playbackState}",
                        kotlin.math.abs(sampled - pausedAt) < 100L)
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                await("Remote play must update playWhenReady") {
                    var playing = false
                    scenario.onActivity { playing = it.player.playWhenReady }
                    playing
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                capture("player-remote-focus-$cycle")
                // Activity STOP is the lifecycle transition caused by Home/background.
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                // ActivityScenario resumes lifecycle callbacks before its separate
                // EmptyActivity window has necessarily relinquished input focus.
                // Run 42435249433 sent MEDIA_PLAY to that other UID (INJECT_EVENTS),
                // despite this Activity already being RESUMED. Keep the actual
                // remote event; require its real target window before injecting it.
                await("Resumed playback window must regain remote input focus") {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused
                }
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
