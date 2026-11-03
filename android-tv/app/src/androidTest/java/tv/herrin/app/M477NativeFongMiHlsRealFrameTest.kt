package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import tv.herrin.app.nativeplayback.NativeFongMiRoute
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** New P0 proxy: real HLS playlist -> fixed-port segment rewrite -> TS -> decoder frames. */
@RunWith(AndroidJUnit4::class)
class M477NativeFongMiHlsRealFrameTest {
    @Test fun nativeFongMiProxyStreamsRealHlsTransportFrames() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val videoTs = inst.context.assets.open("synthetic-segment-00.ts")
            .use { it.readBytes() }
        val manifests = AtomicInteger()
        val segments = AtomicInteger()
        NativeFongMiProxy("approved", { params ->
            assertEquals("jar", params["do"])
            assertEquals("0", params["segment"])
            segments.incrementAndGet()
            arrayOf(200, "video/mp2t", ByteArrayInputStream(videoTs))
        }, { _ ->
            manifests.incrementAndGet()
            val playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:8
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:6.0,
                http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&segment=0
                #EXT-X-ENDLIST
            """.trimIndent() + "\n"
            arrayOf(200, "application/vnd.apple.mpegurl",
                ByteArrayInputStream(playlist.toByteArray(Charsets.UTF_8)))
        }).use { proxy ->
            val url = NativeFongMiRoute.url("proxy://", "approved", proxy)
                ?: error("The authorized static FongMi HLS route must be available")
            assertEquals(NativeFongMiRoute.Health.READY,
                NativeFongMiRoute.probe(url, "approved"))
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                    NativeFongMiRoute.probe(candidate, "approved") == NativeFongMiRoute.Health.READY
                }).probe(PlaybackMedia(
                    url, declaredMime = PlaybackFormatRetry.HLS)))
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", url)
                    .putExtra("fixtureMime", PlaybackFormatRetry.HLS)
            ).use { scenario ->
                var frames = 0
                var error: String? = null
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline && frames < 4 && error == null) {
                    scenario.onActivity {
                        frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        error = it.player.playerError?.errorCodeName
                    }
                    if (frames < 4) Thread.sleep(90)
                }
                assertNull("Native FongMi HLS must not fail Media3", error)
                assertTrue("Required: real TS video decoder frames", frames >= 4)
            }
            assertTrue("Manifest must be received over the new proxy", manifests.get() >= 2)
            assertTrue("Nested TS media must be read through live session", segments.get() >= 1)
        }
    }
}
