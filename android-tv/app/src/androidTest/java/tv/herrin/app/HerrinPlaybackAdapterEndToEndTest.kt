package tv.herrin.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Original synthetic DEX -> real authorized worker -> adapter -> HTTP bytes -> production player. */
@RunWith(AndroidJUnit4::class)
class HerrinPlaybackAdapterEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun selectedOriginalDexEpisodePassesAdapterAndRendersInProductionContainer() = runBlocking {
        val jar = inst.context.assets.open("m467-spider.jar").use { it.readBytes() }
        val expectedMedia = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        val md5 = MessageDigest.getInstance("MD5").digest(jar).joinToString("") { "%02x".format(it) }
        val cached = File(File(inst.targetContext.codeCacheDir, "herrin-spider").apply { mkdirs() }, "$md5.jar")
        if (cached.exists()) assertTrue(cached.delete())
        cached.writeBytes(jar)
        assertTrue(cached.setReadOnly())
        val site = TvBoxSite("Original synthetic DEX", true, key = "m467-original-fixture", spiderApi = "csp_Fixture")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true,
            spiderJar = "https://example.invalid/original-fixture.jar;md5;$md5")
        val bridge = TvBoxSpiderBridge(inst.targetContext)
        try {
            val catalog = bridge.browse(site, config) as SpiderResult.Ready
            val film = (bridge.details(catalog.value.single()) as SpiderResult.Ready).value
            val adapter = HerrinPlaybackEntry.adapter(inst.targetContext, bridge)
            val result = adapter.resolve(HerrinPlaybackRequest(film, 1, "spider-0",
                HerrinPlaybackSource.Spider(site.key), HerrinPlaybackAuthorization(bridge.approvedActiveSiteKey()), allowHttp = true))
            assertTrue("Original playerContent must resolve via adapter: $result", result is PlaybackResult.Ready)
            val media = (result as PlaybackResult.Ready).media
            val connection = URL(media.uri).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
                assertArrayEquals(expectedMedia, connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java).putExtra("fixture", media.uri)
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                var firstFrames = 0
                var firstPosition = 0L
                compose.waitUntil(30_000) {
                    var audio = 0
                    scenario.onActivity {
                        assertNull("Native player error", it.player.playerError)
                        firstFrames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        audio = it.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                        firstPosition = it.player.currentPosition
                    }
                    firstFrames >= 12 && audio >= 12 && firstPosition >= 500L
                }
                val minimumFrames = firstFrames + 12
                val minimumPosition = firstPosition + 400L
                compose.waitUntil(15_000) {
                    var frames = 0
                    var position = 0L
                    scenario.onActivity {
                        assertNull("Native player error after initial playback", it.player.playerError)
                        frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        position = it.player.currentPosition
                    }
                    frames >= minimumFrames && position >= minimumPosition
                }
            }
        } finally {
            bridge.close()
            cached.delete()
        }
    }
}
