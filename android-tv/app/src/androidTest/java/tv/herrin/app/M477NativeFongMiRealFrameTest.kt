package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import tv.herrin.app.nativeplayback.NativeFongMiRoute
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0: the NEW native FongMi server -> real HTTP media bytes -> Media3 frames,
 * not the legacy SpiderLoopbackProxy and not just an accepted URL.
 */
@RunWith(AndroidJUnit4::class)
class M477NativeFongMiRealFrameTest {
    @Test fun approvedSpiderProxyRendersRealMp4Frames() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val video = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        val served = AtomicInteger()
        NativeFongMiProxy("approved", { args ->
            served.incrementAndGet()
            assertEquals("jar", args["do"])
            assertEquals("fixture", args["id"])
            assertNull(args["session"])
            assertEquals("approved", args["siteKey"])
            val range = args["range"]
            val begin = Regex("""bytes=(\d+)-""").find(range.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val finish = Regex("""bytes=\d+-(\d+)""").find(range.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(video.lastIndex)
                ?: video.lastIndex
            val bytes = video.copyOfRange(begin.coerceAtMost(video.size),
                (finish + 1).coerceIn(begin.coerceAtMost(video.size), video.size))
            if (range == null) arrayOf(200, "video/mp4", ByteArrayInputStream(bytes))
            else arrayOf(206, "video/mp4", ByteArrayInputStream(bytes),
                mapOf("Content-Range" to "bytes $begin-$finish/${video.size}",
                    "Accept-Ranges" to "bytes"))
        }, null).use { proxy ->
            val raw = """{"jx":0,"url":"proxy://do=jar&siteKey=approved&id=fixture"}"""
            val normalized = NativeFongMiRoute.playerContent(raw, "approved", proxy)
                ?: error("Native FongMi route must accept the approved Spider")
            val mediaUrl = JSONObject(normalized).getString("url")
            assertTrue(NativeFongMiRoute.isNativeProxy(mediaUrl))
            assertEquals(NativeFongMiRoute.Health.READY,
                NativeFongMiRoute.probe(mediaUrl, "approved"))
            val media = PlaybackMedia(mediaUrl)
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                HerrinRemoteMediaProbe().probe(media))
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", mediaUrl)
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
                assertNull("New native proxy must deliver video to Media3", failure)
                assertTrue("Require actual decoder frames, not URL-only success", frames >= 4)
            }
            assertTrue("Probe and player must read the actual native proxy bytes",
                served.get() >= 2)
        }
    }
}
