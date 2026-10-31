package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FongMi-style type=1 JSON parser -> user-authorized Media3 media. Both the
 * parser response and synthetic video are supplied by one local test server.
 */
@RunWith(AndroidJUnit4::class)
class M459JsonParserPlaybackTest {
    @Test fun configuredJsonParserToMedia3RendersActualVideoFrames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val video = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/parse?url=") == true)
                        return MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"code":200,"url":"http://127.0.0.1:${server.port}/media.mp4","format":"video/mp4"}""")
                    if (request.path?.startsWith("/media.mp4") == true)
                        return MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(video))
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = "https://watch.example.test/film/42.html"
            val parser = TvBoxJsonParse("Test JSON",
                server.url("/parse?url=").toString())
            assertEquals(parser, TvBoxJsonPlayback.choose(source, "official", listOf(parser)))
            val resolved = runBlocking { TvBoxJsonPlayback.resolve(parser, source, allowHttp = true) }
            assertTrue(resolved is PlaybackResult.Ready)
            val media = (resolved as PlaybackResult.Ready).media
            assertEquals("http://127.0.0.1:${server.port}/media.mp4", media.uri)
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", media.uri)
            ).use { scenario ->
                var frames = 0
                var failure: String? = null
                val deadline = System.currentTimeMillis() + 22_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        failure = it.player.playerError?.errorCodeName
                    }
                    if (failure != null) fail("Parsed JSON media decode error: $failure")
                    if (frames > 3) break
                    Thread.sleep(100)
                }
                assertTrue("Parsed media must actually decode 4+ frames", frames > 3)
                assertTrue("Parser + media must both make real requests",
                    server.requestCount >= 2)
            }
        }
    }
}
