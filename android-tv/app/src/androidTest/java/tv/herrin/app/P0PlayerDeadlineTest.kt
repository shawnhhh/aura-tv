package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import androidx.media3.common.PlaybackException

/** The production manager must terminate an unresponsive media stream and release on Back. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class P0PlayerDeadlineTest {
    @Test fun noResponseTimesOutOnceAndCloseCancelsNextDeadline() {
        val inst = InstrumentationRegistry.getInstrumentation()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            lateinit var adapter: HerrinPlaybackAdapter
            val errors = LinkedBlockingQueue<PlaybackException>()
            inst.runOnMainSync {
                adapter = HerrinPlaybackAdapter(inst.targetContext, PlaybackMedia(server.url("/blocked.mp4").toString()))
                adapter.manager.onTimeout = { errors.offer(it) }
                adapter.manager.start(com.fongmi.android.tv.player.media.PlaySpec("deadline", adapter.media), timeout = 300)
            }
            try {
                val error = requireNotNull(errors.poll(4, TimeUnit.SECONDS)) { "Unresponsive media must not buffer forever" }
                assertEquals(PlaybackException.ERROR_CODE_TIMEOUT, error.errorCode)
                inst.runOnMainSync {
                    assertEquals(androidx.media3.common.Player.STATE_IDLE, adapter.player.playbackState)
                    adapter.manager.start(com.fongmi.android.tv.player.media.PlaySpec("cancel", adapter.media), timeout = 300)
                    adapter.close()
                }
                assertNull("Closed player must not publish a stale timeout", errors.poll(700, TimeUnit.MILLISECONDS))
            } finally { inst.runOnMainSync { adapter.close() } }
        }
    }
}
