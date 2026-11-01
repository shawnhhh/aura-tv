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

/** The backend browser runs headlessly; no Herrin activity, UI or TMDB is altered. */
@RunWith(AndroidJUnit4::class)
class M461BrowserPlaybackTest {
    @Test fun browserType0SniffsRealMediaThenMedia3RendersFrames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val original = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/jx?url=") == true ->
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("""<!doctype html><html><head><title>Local playback test</title></head>
                                <body><script>fetch('/clip.mp4').catch(() => {});</script></body></html>""")
                    request.path?.startsWith("/clip.mp4") == true ->
                        MockResponse().setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(original))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val parser = TvBoxPlaybackParser("Browser test", 0,
                server.url("/jx?url=").toString())
            val request = "https://watch.example.test/example.html"
            val route = FongMiPlaybackRouter.route(
                """{"playUrl":"parse:Browser test","parse":1,
                    "url":"https://watch.example.test/example.html"}""",
                "web", emptyList(),true,playbackParsers=listOf(parser))
            assertTrue(route is FongMiPlayRoute.BrowserParser)
            val parsed = runBlocking {
                FongMiBrowserPlayback.resolve(inst.targetContext, parser, request, true)
            }
            assertTrue("Headless parser must observe actual media request: $parsed",
                parsed is PlaybackResult.Ready)
            val media = (parsed as PlaybackResult.Ready).media
            assertEquals(server.url("/clip.mp4").toString(), media.uri)
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", media.uri)
            ).use { scenario ->
                var rendered = 0
                var error: String? = null
                val deadline = System.currentTimeMillis() + 22_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        rendered = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        error = it.player.playerError?.errorCodeName
                    }
                    if (error != null) fail("Browser-resolved video failed: $error")
                    if (rendered > 3) break
                    Thread.sleep(100)
                }
                assertTrue("Parsed browser video must render actual Media3 frames", rendered > 3)
            }
        }
    }
}
