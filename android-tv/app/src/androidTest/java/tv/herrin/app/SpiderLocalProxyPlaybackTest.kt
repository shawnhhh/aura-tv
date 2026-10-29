package tv.herrin.app

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.catvod.Proxy
import com.github.catvod.crawler.Spider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** CatVod's real getProxyUrl() -> Java Spider.proxy() -> HTTP -> real Media3 frames. */
@RunWith(AndroidJUnit4::class)
class SpiderLocalProxyPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun spiderGeneratedAuthenticatedProxyUrlProducesRealDecodedVideoFrames() {
        val mp4 = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        assertTrue(mp4.size > 100)
        val calls = AtomicInteger()
        SpiderLoopbackProxy("authorized-site") { params ->
            assertEquals("authorized-site", params["siteKey"])
            assertEquals("jar", params["do"])
            assertEquals("movie", params["part"])
            calls.incrementAndGet()
            arrayOf(200, "video/mp4", ByteArrayInputStream(mp4),
                mapOf("Cache-Control" to "no-store"))
        }.use { proxy ->
            Proxy.set(proxy.port, proxy.token)
            try {
                val fakeSpider = object : Spider() {}
                fakeSpider.siteKey = "authorized-site"
                val url = fakeSpider.getProxyUrl(mapOf("part" to "movie"))
                assertTrue(url.startsWith("http://127.0.0.1:"))
                assertTrue(url.contains("session="))
                assertTrue(url.contains("siteKey="))
                val json = JSONObject().put("parse", 0).put("format", "mp4")
                    .put("url", url).toString()
                val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("spiderJson", json)
                    .putExtra("spiderAllowHttp", true)
                ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                    compose.waitUntil(30_000) {
                        var frames = 0
                        scenario.onActivity {
                            frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        }
                        frames > 3
                    }
                    assertTrue("Media3 must request the Java Spider proxy", calls.get() > 0)
                    compose.onNodeWithTag("player-verification-toggle").performClick()
                    compose.waitUntil(30_000) {
                        runCatching {
                            compose.onNodeWithTag("player-journey-status")
                                .assertTextContains("已输出视频帧", substring = true)
                        }.isSuccess
                    }
                }
            } finally {
                Proxy.set(-1, "")
            }
        }
    }
}
