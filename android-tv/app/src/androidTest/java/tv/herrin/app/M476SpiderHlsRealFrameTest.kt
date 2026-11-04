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
 * Non-mocked full HLS chain: fixed-port FongMi references inside Spider's
 * manifest -> safe live-session rewrite -> Media3 fetches TS segment ->
 * actual decoder buffers on Android TV emulator Surface.
 */
@RunWith(AndroidJUnit4::class)
class M476SpiderHlsRealFrameTest {
    @Test fun hlsNestedSegmentsFromOldTvboxProxyDecodeRealFrames() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val segment = inst.context.assets.open("synthetic-segment-00.ts")
            .use { it.readBytes() }
        val seenManifest = AtomicInteger()
        val seenSegments = AtomicInteger()
        SpiderLoopbackProxy("approved",
            staticHandler = { params ->
                seenManifest.incrementAndGet()
                val manifest = """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:8
                    #EXT-X-MEDIA-SEQUENCE:0
                    #EXTINF:6.0,
                    http://127.0.0.1:9978/proxy?do=jar&siteKey=approved&segment=0
                    #EXT-X-ENDLIST
                """.trimIndent() + "\n"
                arrayOf(200, "application/vnd.apple.mpegurl",
                    ByteArrayInputStream(manifest.toByteArray(Charsets.UTF_8)))
            }) { params ->
                assertEquals("jar", params["do"])
                assertEquals("approved", params["siteKey"])
                assertEquals("0", params["segment"])
                seenSegments.incrementAndGet()
                arrayOf(200, "video/mp2t", ByteArrayInputStream(segment))
            }.use { proxy ->
            val url = SpiderProxyPlayback.rebaseUrl(
                "proxy://", "approved", proxy) ?: error("Static HLS route not authorized")
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                    val uri = runCatching { java.net.URI(candidate) }.getOrNull()
                    uri?.scheme == "http" && uri.host == "127.0.0.1" &&
                        uri.port == proxy.port && uri.path == "/proxy" &&
                        uri.rawQuery?.split('&')?.any { it == "session=${proxy.token}" } == true
                }).probe(PlaybackMedia(url,
                    declaredMime = PlaybackFormatRetry.HLS)))
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
                assertNull("Authenticated FongMi HLS proxy must reach Media3", error)
                assertTrue("HLS must decode actual synthetic transport-stream frames",
                    frames >= 4)
            }
            assertTrue("HLS playlist must have been requested", seenManifest.get() >= 2)
            assertTrue("Media3 must fetch at least one rewritten TS segment",
                seenSegments.get() >= 1)
        }
    }
}
