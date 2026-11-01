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

/** Original compiled DEX JAR -> real :spider loader/Init/playerContent -> HTTP -> Media3. */
@RunWith(AndroidJUnit4::class)
class M467JarPlaybackEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun originalDexJarStaticAndInstanceRoutesRenderAndReopenWithNewSession() = runBlocking {
        val jar = inst.context.assets.open("m467-spider.jar").use { it.readBytes() }
        val mediaBytes = inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
        val md5 = MessageDigest.getInstance("MD5").digest(jar).joinToString("") { "%02x".format(it) }
        // Use the production verified cache path. This does not weaken HTTPS/hash policy:
        // the worker rechecks the entire archive against md5 before loading any DEX.
        val directory = File(inst.targetContext.codeCacheDir, "herrin-spider").apply { mkdirs() }
        val cached = File(directory, "$md5.jar")
        if (cached.exists()) assertTrue(cached.delete())
        cached.writeBytes(jar)
        assertTrue(cached.setReadOnly())
        val site = TvBoxSite("Original synthetic JAR", needsAdapter = true,
            key = "m467-original-fixture", spiderApi = "csp_Fixture")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true,
            spiderJar = "https://example.invalid/original-fixture.jar;md5;$md5")
        val bridge = TvBoxSpiderBridge(inst.targetContext)
        var oldUri: String? = null
        try {
            for (round in 0..1) {
                val catalog = bridge.browse(site, config)
                assertTrue("Real DEX initializer/home must run: $catalog", catalog is SpiderResult.Ready)
                val films = (catalog as SpiderResult.Ready).value
                assertEquals(1, films.size)
                val details = bridge.details(films.single())
                assertTrue(details is SpiderResult.Ready)
                val film = (details as SpiderResult.Ready).value
                for ((episode, route) in listOf(1 to "instance", 2 to "static")) {
                    val resolved = bridge.play(film, episode, "spider-0", true)
                    assertTrue("Actual JAR playerContent must resolve: $resolved", resolved is PlaybackResult.Ready)
                    val media = (resolved as PlaybackResult.Ready).media
                    val request = URL(media.uri).openConnection() as HttpURLConnection
                    try {
                        request.connectTimeout = 3_000
                        request.readTimeout = 5_000
                        assertEquals(200, request.responseCode)
                        assertEquals(route, request.getHeaderField("X-Herrin-Fixture-Route"))
                        assertArrayEquals(mediaBytes, request.inputStream.use { it.readBytes() })
                    } finally { request.disconnect() }
                    if (round == 1 && oldUri != null) assertNotEquals(oldUri, media.uri)
                    val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                        .putExtra("fixture", media.uri)
                    ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                        compose.waitUntil(30_000) {
                            var frames = 0
                            scenario.onActivity {
                                frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                            }
                            frames > 3
                        }
                    }
                    oldUri = media.uri
                }
                bridge.close()
                val closed = oldUri!!
                compose.waitUntil(8_000) {
                    !runBlocking { SpiderProxyPlayback.ping(closed) }
                }
            }
        } finally {
            bridge.close()
            cached.delete()
        }
    }
}
