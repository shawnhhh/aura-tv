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
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
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
            for (round in 0..2) {
                val catalog = bridge.browse(site, config)
                assertTrue("Real DEX initializer/home must run: $catalog", catalog is SpiderResult.Ready)
                val films = (catalog as SpiderResult.Ready).value
                assertEquals(1, films.size)
                val details = bridge.details(films.single())
                assertTrue(details is SpiderResult.Ready)
                var film = (details as SpiderResult.Ready).value
                val selections = listOf(1 to "spider-0", 1 to "spider-0", 2 to "spider-0", 1 to "spider-0", 1 to "spider-1", 1 to "spider-0")
                val sessions = mutableSetOf<String>()
                for ((index, selection) in selections.withIndex()) {
                    val (episode, line) = selection
                    val route = if (episode == 2) "static" else "instance"
                    val session = PlaybackDiagnosticSession.newId()
                    assertTrue(sessions.add(session))
                    val resolved = kotlinx.coroutines.withContext(PlaybackDiagnosticSession.context(session)) { bridge.play(film, episode, line, true) }
                    assertTrue("Actual JAR playerContent must resolve: $resolved", resolved is PlaybackResult.Ready)
                    val media = (resolved as PlaybackResult.Ready).media
                    val request = URL(media.uri).openConnection() as HttpURLConnection
                    try {
                        request.connectTimeout = 3_000
                        request.readTimeout = 5_000
                        assertEquals(200, request.responseCode)
                        assertEquals(if (route == "static") "en" else "zh",
                            request.getHeaderField("Content-Language"))
                        assertArrayEquals(mediaBytes, request.inputStream.use { it.readBytes() })
                    } finally { request.disconnect() }
                    assertEquals(session, media.diagnosticSessionId)
                    if (round > 0 && index == 0 && oldUri != null) assertNotEquals(oldUri, media.uri)
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
                        var initialPosition = 0L
                        scenario.onActivity { initialPosition = it.player.currentPosition.toLong() }
                        compose.waitUntil(8_000) {
                            var advanced = false
                            scenario.onActivity { advanced = it.player.currentPosition > initialPosition + 500 }
                            advanced
                        }
                        scenario.onActivity { it.player.pause(); it.player.seekTo(2_000) }
                        compose.waitUntil(8_000) {
                            var sought = false
                            scenario.onActivity { sought = !it.player.playWhenReady && it.player.currentPosition == 2_000L }
                            sought
                        }
                    }
                    oldUri = media.uri
                    if (index == 0 || index == 4) {
                        // Detail return: release the old worker/proxy, then start a new approved operation.
                        inst.runOnMainSync { bridge.releasePlayback() }
                        bridge.awaitPlaybackReleaseForInstrumentation()
                        assertFalse(SpiderProxyPlayback.ping(media.uri))
                        val fresh = bridge.browse(site, config) as SpiderResult.Ready
                        film = (bridge.details(fresh.value.single()) as SpiderResult.Ready).value
                    }
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
