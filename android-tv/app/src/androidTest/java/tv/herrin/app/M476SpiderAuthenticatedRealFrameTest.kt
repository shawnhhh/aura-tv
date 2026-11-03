package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0 real execution: approved CatVod Java proxy -> authenticated random-port
 * loopback -> bounded media bytes -> the production Media3 player -> decoded
 * frames on an actual Android TV emulator Surface. No mocked "ready" result.
 */
@RunWith(AndroidJUnit4::class)
class M476SpiderAuthenticatedRealFrameTest {
    @Test fun approvedJavaSpiderRendersActualVideoFramesThroughProxy() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val video = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        val requests = AtomicInteger()
        SpiderLoopbackProxy("verified-spider") { params ->
            requests.incrementAndGet()
            assertEquals("jar", params["do"])
            assertEquals("verified-spider", params["siteKey"])
            assertEquals("fixture-video", params["id"])
            val range = params["range"]
            val begin = Regex("""bytes=(\d+)-""").find(range.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val finish = Regex("""bytes=\d+-(\d+)""").find(range.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(video.lastIndex)
                ?: video.lastIndex
            val data = video.copyOfRange(begin.coerceAtMost(video.size),
                (finish + 1).coerceIn(begin.coerceAtMost(video.size), video.size))
            if (range == null) arrayOf(200, "video/mp4", ByteArrayInputStream(data))
            else arrayOf(206, "video/mp4", ByteArrayInputStream(data),
                mapOf("Content-Range" to "bytes $begin-$finish/${video.size}",
                    "Accept-Ranges" to "bytes"))
        }.use { proxy ->
            val media = SpiderProxyPlayback.rebind(
                PlaybackMedia("proxy://do=jar&siteKey=verified-spider&id=fixture-video"),
                "verified-spider", proxy) as? PlaybackResult.Ready
                ?: throw AssertionError("Approved Java route must resolve")
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                HerrinRemoteMediaProbe().probe(media.media))
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", media.media.uri)
            ).use { scenario ->
                var frames = 0
                var failure: String? = null
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline && frames < 4 && failure == null) {
                    scenario.onActivity {
                        frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        failure = it.player.playerError?.errorCodeName
                    }
                    if (frames < 4) Thread.sleep(90)
                }
                assertNull("Media3 must accept authenticated Spider video", failure)
                assertTrue("Actual pixels/decoder buffers required, not a URL-only test",
                    frames >= 4)
            }
            assertTrue("Probe and player must both contact the real Java proxy",
                requests.get() >= 2)
        }
    }
}
