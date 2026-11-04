package tv.herrin.app

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.herrin.app.nativeplayback.player.PlayerManager
import java.util.concurrent.atomic.AtomicInteger

/** Runs only checked-in synthetic media through the real official Media3 engine. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativePlayerManagerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun managerInstallsSourceHeadersDecodesAudioVideoAndReleases() {
        val server = MockWebServer()
        val bytes = inst.context.assets.open("synthetic-remote.mp4").use { it.readBytes() }
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4")
            .setBody(Buffer().write(bytes))) }
        server.start()
        try {
            ActivityScenario.launch<PlaybackTestActivity>(Intent(inst.targetContext,
                PlaybackTestActivity::class.java)
                .putExtra("fixture", server.url("/movie.mp4").toString())
                .putExtra("fixtureAuthorization", "Bearer synthetic-manager-only")).use { scenario ->
                fun until(message: String, condition: (PlaybackTestActivity) -> Boolean) {
                    val end = SystemClock.elapsedRealtime() + 20000
                    var matched = false
                    while (!matched && SystemClock.elapsedRealtime() < end) {
                        scenario.onActivity {
                            if (it.createdPlayers.isNotEmpty()) {
                                assertNull(it.player.playerError)
                                matched = condition(it)
                            }
                        }
                        if (!matched) Thread.sleep(50)
                    }
                    assertTrue(message, matched)
                }
                until("Sustained real video/audio output required") {
                    (it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) >= 12 &&
                        (it.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) >= 12 &&
                        it.player.currentPosition >= 500L
                }
                var firstFrames = 0
                var firstPosition = 0L
                scenario.onActivity {
                    firstFrames = it.player.videoDecoderCounters!!.renderedOutputBufferCount
                    firstPosition = it.player.currentPosition
                    assertEquals(C.USAGE_MEDIA, it.player.audioAttributes.usage)
                    assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, it.player.audioAttributes.contentType)
                }
                until("Decoded output and position must continue advancing") {
                    it.player.videoDecoderCounters!!.renderedOutputBufferCount >= firstFrames + 12 &&
                        it.player.currentPosition >= firstPosition + 400L
                }
                val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("Bearer synthetic-manager-only", request!!.getHeader("Authorization"))
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                until("Physical remote PAUSE must pause the manager's player") { !it.player.playWhenReady }
                var oldAudio = ""
                scenario.onActivity {
                    assertTrue(PlaybackTransport.options(it.player, C.TRACK_TYPE_AUDIO).size > 1)
                    oldAudio = PlaybackTransport.currentLabel(it.player, C.TRACK_TYPE_AUDIO)
                }
                compose.onNodeWithTag("transport-audio")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.waitForIdle()
                compose.onNodeWithTag("transport-audio").assertIsFocused()
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                until("D-pad CENTER must change the actual selected audio track") {
                    PlaybackTransport.currentLabel(it.player, C.TRACK_TYPE_AUDIO) != oldAudio
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                until("Physical remote PLAY must resume real decoding") {
                    it.player.isPlaying &&
                        (it.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) >= 12
                }
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                until("Back must release manager and native player") { it.player.isReleased }
            }
        } finally { server.shutdown() }
    }

    @Test fun foregroundPauseSuspendsDeadlineUntilPlay() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val errors = AtomicInteger()
        lateinit var manager: PlayerManager
        try {
            inst.runOnMainSync {
                manager = PlayerManager(inst.targetContext, PlaybackMedia(server.url("/waiting.mp4").toString()))
                manager.onTimeout = { errors.incrementAndGet() }
                manager.start(manager.spec(), timeoutMs = 100L)
                manager.setForeground(false)
                manager.setForeground(true)
            }
            Thread.sleep(200)
            assertEquals("Returning from background must preserve paused state", 0, errors.get())
            inst.runOnMainSync { manager.player.play() }
            val end = SystemClock.elapsedRealtime() + 3000L
            while (errors.get() == 0 && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
            assertEquals("PLAY during existing buffering must start a fresh deadline", 1, errors.get())
        } finally {
            inst.runOnMainSync { manager.release() }
            server.shutdown()
        }
    }

    @Test fun stoppedManagerCannotDeliverItsOldLoadTimeout() {
        val errors = AtomicInteger()
        lateinit var manager: PlayerManager
        inst.runOnMainSync {
            manager = PlayerManager(inst.targetContext, PlaybackMedia("http://127.0.0.1:9/synthetic.mp4"))
            manager.onTimeout = { errors.incrementAndGet() }
            manager.start(manager.spec(), timeoutMs = 100L)
            manager.stop()
        }
        Thread.sleep(250)
        inst.runOnMainSync { manager.release() }
        assertEquals(0, errors.get())
    }
}
