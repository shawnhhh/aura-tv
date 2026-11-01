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

    @Test fun currentAndLegacyFongMiSpiderMethodsResolveAndShareSecureLoopbackPort() {
        // Real binary JARs are linked against exact Java method descriptors.
        // Missing proxyUrl(Map, boolean) or dialog(JSONObject, Callback) in the
        // vendored Spider superclass fails before playerContent can play.
        val clazz = Spider::class.java
        assertNotNull(clazz.getMethod("proxyUrl"))
        assertNotNull(clazz.getMethod("proxyUrl", Map::class.java))
        assertNotNull(clazz.getMethod("proxyUrl", Map::class.java, java.lang.Boolean.TYPE))
        assertNotNull(clazz.getMethod("danmakuUrl", String::class.java))
        assertNotNull(clazz.getMethod("danmakuUrl", String::class.java, String::class.java))
        assertNotNull(clazz.getMethod("dialog", JSONObject::class.java))
        assertNotNull(clazz.getMethod("dialog", JSONObject::class.java,
            com.github.catvod.utils.Dialog.Callback::class.java))
        SpiderLoopbackProxy("abi-site") { params ->
            assertEquals("test", params["part"])
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(7, 8, 9)))
        }.use { proxy ->
            Proxy.set(proxy.port, proxy.token)
            try {
                val spider = object : Spider() {}
                spider.siteKey = "abi-site"
                val options = mapOf("part" to "test")
                val current = spider.proxyUrl(options)
                val legacy = spider.getProxyUrl(options)
                assertEquals(legacy, current)
                assertEquals(current, spider.proxyUrl(options, false))
                assertEquals(current, spider.proxyUrl(options, true))
                assertTrue(current.startsWith("http://127.0.0.1:"))
                val request = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                request.connectTimeout = 2_000
                request.readTimeout = 2_000
                assertEquals(200, request.responseCode)
                assertArrayEquals(byteArrayOf(7, 8, 9), request.inputStream.use { it.readBytes() })
                request.disconnect()
            } finally { Proxy.set(-1, "") }
        }
    }

    @Test fun extensionlessSpiderProxyHlsRecoversFromProgressiveAndRendersSegments() {
        val hls = inst.context.assets.open("synthetic-hls.m3u8")
            .bufferedReader().use { it.readText() }
        val segmentPattern = Regex("synthetic-segment-[0-9]{2}\\.ts")
        var playlist = byteArrayOf()
        val segmentRequests = AtomicInteger()
        SpiderLoopbackProxy("hls-site") { params ->
            val part = params["part"].orEmpty()
            when {
                part == "playlist" -> arrayOf(200, "application/vnd.apple.mpegurl",
                    ByteArrayInputStream(playlist))
                segmentPattern.matches(part) -> {
                    segmentRequests.incrementAndGet()
                    arrayOf(200, "video/mp2t", ByteArrayInputStream(
                        inst.context.assets.open(part).use { it.readBytes() }))
                }
                else -> arrayOf(404, "text/plain", ByteArrayInputStream(byteArrayOf()))
            }
        }.use { proxy ->
            Proxy.set(proxy.port, proxy.token)
            try {
                val spider = object : Spider() {}
                spider.siteKey = "hls-site"
                playlist = segmentPattern.replace(hls) { match ->
                    spider.getProxyUrl(mapOf("part" to match.value))
                }.toByteArray(Charsets.UTF_8)
                // No declared format: /proxy must retry the same source as HLS
                // rather than assume it is a progressive media file.
                val json = JSONObject().put("parse", 0)
                    .put("url", spider.getProxyUrl(mapOf("part" to "playlist")))
                    .toString()
                val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("spiderJson", json).putExtra("spiderAllowHttp", true)
                ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                    compose.waitUntil(35_000) {
                        var frames = 0
                        scenario.onActivity {
                            frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        }
                        frames > 3
                    }
                    assertTrue("Spider must serve actual HLS video segments",
                        segmentRequests.get() > 0)
                    TvVisibleFrameEvidence.verify(inst, "m433-catvod-hls-visible-frame")
                }
            } finally {
                Proxy.set(-1, "")
            }
        }
    }

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
                    TvVisibleFrameEvidence.verify(inst, "m433-catvod-mp4-visible-frame")
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
    @Test fun isolatedBinderProcessMustRemainBoundUntilMediaProxyHealthSucceeds() {
        // Smoke test deliberately uses only local loopback, never a real provider.
        SpiderLoopbackProxy("ipc-site") {
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(1, 2)))
        }.use { proxy ->
            val uri = "http://127.0.0.1:${proxy.port}/proxy?session=${proxy.token}&do=jar&siteKey=ipc-site"
            assertTrue(kotlinx.coroutines.runBlocking { SpiderProxyPlayback.ping(uri) })
        }
    }

    @Test fun fongMiProxySchemeNormalizesToRealAuthenticatedPlaybackBeforeRouting() {
        val samples = byteArrayOf(2, 4, 6, 8, 10)
        SpiderLoopbackProxy("legacy-fongmi-site") { params ->
            assertEquals("jar", params["do"])
            assertEquals("legacy-fongmi-site", params["siteKey"])
            assertEquals("episode-one", params["part"])
            arrayOf(200, "video/mp4", ByteArrayInputStream(samples))
        }.use { proxy ->
            val raw = JSONObject()
                .put("parse", 0)
                .put("format", "mp4")
                .put("url", "proxy://do=jar&siteKey=legacy-fongmi-site&part=episode-one")
                .toString()
            val fixed = SpiderProxyPlayback.normalizePlayerResponse(
                raw, "legacy-fongmi-site", proxy)
            assertNotNull(fixed)
            val routed = FongMiPlaybackRouter.route(
                fixed!!, "夸父原", emptyList(), allowHttp = true)
            assertTrue("Internal proxy:// must become a direct playback decision",
                routed is FongMiPlayRoute.Direct)
            val media = (routed as FongMiPlayRoute.Direct).playback.media
            assertTrue(SpiderProxyPlayback.isCatVodProxy(media.uri))
            assertEquals(SpiderProxyPlayback.Health.READY,
                kotlinx.coroutines.runBlocking { SpiderProxyPlayback.diagnose(media.uri) })
            val result = SpiderProxyPlayback.rebind(media, "legacy-fongmi-site", proxy)
            assertTrue(result is PlaybackResult.Ready)
            val url = (result as PlaybackResult.Ready).media.uri
            assertArrayEquals(samples, java.net.URL(url).openStream().use { it.readBytes() })
        }
    }

    @Test fun fongMiProxySchemeRejectsOtherSitesAndInvalidRoutes() {
        SpiderLoopbackProxy("current-site") {
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            val unsafe = listOf(
                "proxy://do=jar&siteKey=other-site&part=x",
                "proxy://do=external&siteKey=current-site",
                "proxy://part=x&part=y",
                "proxy://url=http://attacker.example"
            )
            for (url in unsafe) {
                assertNull("Must not rewrite unsafe proxy alias: $url",
                    SpiderProxyPlayback.normalizePlayerResponse(
                        JSONObject().put("url", url).toString(), "current-site", proxy))
            }
            val supported = JSONObject().put("url", JSONObject()
                .put("position", 1).put("values", org.json.JSONArray()
                    .put(JSONObject().put("name", "A").put("v", "https://example.org/a.mp4"))
                    .put(JSONObject().put("name", "B")
                        .put("v", "proxy://do=jar&siteKey=current-site&part=selected"))))
            val adjusted = SpiderProxyPlayback.normalizePlayerResponse(
                supported.toString(), "current-site", proxy)
            assertNotNull(adjusted)
            val values = JSONObject(adjusted!!).getJSONObject("url").getJSONArray("values")
            assertEquals("https://example.org/a.mp4",
                values.getJSONObject(0).getString("v"))
            assertTrue(values.getJSONObject(1).getString("v").contains("session="))
        }
    }

    @Test fun actualIsolatedSpiderProcessStreamsAuthenticatedProxyAcrossBinder() {
        val bridge = TvBoxSpiderBridge(inst.targetContext)
        try {
            val response = kotlinx.coroutines.runBlocking {
                bridge.probeRemoteProxyForInstrumentation()
            }
            assertTrue("Binder must start :spider and return its private proxy URL",
                response is SpiderResult.Ready)
            val url = (response as SpiderResult.Ready).value
            assertEquals(SpiderProxyPlayback.Health.READY,
                kotlinx.coroutines.runBlocking { SpiderProxyPlayback.diagnose(url) })
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                assertEquals(200, connection.responseCode)
                assertArrayEquals(byteArrayOf(8, 9, 10),
                    connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        } finally {
            bridge.close()
        }
    }

}
