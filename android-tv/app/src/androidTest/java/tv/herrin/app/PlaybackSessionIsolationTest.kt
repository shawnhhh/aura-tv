package tv.herrin.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Media3 teardown must persist the outgoing episode to its own immutable session. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackSessionIsolationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun replacingMediaInPlaceKeepsDisposeProgressInTheOutgoingSession() {
        fun fixture(asset: String, filename: String): String {
            val file = File(inst.targetContext.cacheDir, filename)
            inst.context.assets.open(asset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file.toURI().toString()
        }
        val outgoingUri = fixture("synthetic-remote.mp4", "session-outgoing.mp4")
        val incomingUri = fixture("synthetic.mp4", "session-incoming.mp4")
        val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
            .putExtra("fixture", outgoingUri)
        ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
            lateinit var outgoingPlayer: ExoPlayer
            compose.waitUntil(20_000) {
                var decoded = false
                scenario.onActivity {
                    outgoingPlayer = it.player
                    decoded = (it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3
                }
                decoded
            }
            scenario.onActivity { it.player.pause(); it.player.seekTo(12_000L) }
            compose.waitUntil(8_000) {
                var pausedAtTwelve = false
                scenario.onActivity {
                    pausedAtTwelve = !it.player.playWhenReady && it.player.currentPosition == 12_000L
                }
                pausedAtTwelve
            }
            scenario.onActivity {
                // Clear earlier periodic samples so this proves the disposal callback itself.
                it.progressEvents.clear()
                it.switchFixtureInPlace(incomingUri)
            }
            var handoffState = "not observed"
            try {
                compose.waitUntil(20_000) {
                    var switchedAndDecoded = false
                    scenario.onActivity {
                        val current = it.player
                        val frames = current.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        handoffState = "samePlayer=${current === outgoingPlayer}, " +
                            "outgoingReleased=${outgoingPlayer.isReleased}, state=${current.playbackState}, " +
                            "playWhenReady=${current.playWhenReady}, frames=$frames, " +
                            "error=${current.playerError?.errorCodeName}, created=${it.createdPlayers.size}, " +
                            "incomingReleased=${current.isReleased}, items=${current.mediaItemCount}, " +
                            "states=${it.states.toList().takeLast(12)}"
                        switchedAndDecoded = current !== outgoingPlayer && outgoingPlayer.isReleased && frames > 3
                    }
                    switchedAndDecoded
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Media handoff did not release and decode: $handoffState", timeout)
            }
            compose.waitUntil(10_000) {
                var newSessionReported = false
                scenario.onActivity {
                    newSessionReported = it.progressEvents.any { event -> event.sessionUri == incomingUri }
                }
                newSessionReported
            }
            scenario.onActivity { activity ->
                val events = activity.progressEvents.toList()
                assertTrue("Outgoing onDispose must save its paused twelve-second position",
                    events.any { it.sessionUri == outgoingUri && it.positionMs == 12_000L })
                assertFalse("Incoming episode must never receive the outgoing playback position",
                    events.any { it.sessionUri == incomingUri && it.positionMs >= 12_000L })
                assertTrue("Incoming callbacks must contain only its own six-second timeline",
                    events.filter { it.sessionUri == incomingUri }.all {
                        it.positionMs in 0L..6_100L && it.durationMs in 1L..6_100L
                    })
            }
        }
    }
}
