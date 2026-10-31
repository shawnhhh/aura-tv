package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A non-cloud TVBox CMS result travels all the way from the selected HTTP
 * episode URL to real Media3-decoded video frames. No remote third-party
 * stream or cloud credentials are involved; the fixture is original video.
 */
@RunWith(AndroidJUnit4::class)
class M458CmsHttpPlaybackTest {
    @Test fun selectedCmsHttpVideoRendersRealFramesWithoutNetdiskOrSpider() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val video = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/play?id=42") == true)
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(video))
                    else MockResponse().setResponseCode(404)
            }
            server.start()
            val url = server.url("/play?id=42").toString()
            val film = TvBoxVodCodec.decode("""{"list":[{
                "vod_id":"m458", "vod_name":"自制非网盘影片",
                "vod_play_from":"官方","vod_play_url":"正片${'$'}${url}"
            }]}""", allowHttp = true).single()
            val resolved = TvBoxCmsPlaybackResolver.resolve(
                film, episode = 1, line = "cms-0", allowHttp = true)
            assertTrue(resolved is PlaybackResult.Ready)
            val media = (resolved as PlaybackResult.Ready).media
            assertEquals(url, media.uri)

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
                    if (error != null) fail("HTTP CMS fixture playback failed: $error")
                    if (rendered > 3) break
                    Thread.sleep(100)
                }
                assertTrue("Selected non-cloud HTTP CMS must decode real video frames", rendered > 3)
                assertTrue("Media3 must request the selected CMS URL", server.requestCount > 0)
            }
        }
    }
}
