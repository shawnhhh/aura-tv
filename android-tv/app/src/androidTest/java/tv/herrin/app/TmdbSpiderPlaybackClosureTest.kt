package tv.herrin.app

import android.content.Intent
import android.content.Context
import android.app.ActivityManager
import android.os.Process
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Original DEX -> signed source behind TMDB metadata -> real Proxy/Media3 video and PCM buffers. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TmdbSpiderPlaybackClosureTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun originalSignedSourceBehindTmdbDetailProducesVideoAndDecodedAudio() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val jar = inst.context.assets.open("m467-spider.jar").use { it.readBytes() }
        val md5 = MessageDigest.getInstance("MD5").digest(jar).joinToString("") { "%02x".format(it) }
        val cached = File(File(inst.targetContext.codeCacheDir, "herrin-spider").apply { mkdirs() }, "$md5.jar")
        if (cached.exists()) check(cached.delete())
        cached.writeBytes(jar); check(cached.setReadOnly())
        val site = TvBoxSite("Original source", true, key = "tmdb-original", spiderApi = "csp_Fixture")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true,
            spiderJar = "https://example.invalid/original.jar;md5;$md5")
        val context = SpiderRuntimeCompatIpcTest.DeathRecordingContext(inst.targetContext)
        val worker = TvBoxSpiderBridge(context)
        try {
            val listed = (worker.browse(site, config) as SpiderResult.Ready).value.single()
            val raw = (worker.details(listed) as SpiderResult.Ready).value
            val display = Film("tmdb-movie-42", "Original TMDB metadata", "", "电影", "2026", "", 0,
                spiderVodId = raw.spiderVodId, playbackEpisodes = raw.playbackEpisodes)
            assertTrue("Old display Film cannot authorize playerContent",
                worker.play(display, 1, "spider-0", true) is PlaybackResult.Unavailable)
            val routes = HerrinTmdbPlaybackRouteStore().apply { spider(display.id, site.key, raw) }
            var oldUri: String? = null
            for (attempt in 0..1) {
                if (attempt == 1) {
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val process = manager.runningAppProcesses.single { it.processName == "${context.packageName}:spider" }
                    assertNotEquals(Process.myPid(), process.pid)
                    Process.killProcess(process.pid)
                    withTimeout(10_000) { context.disconnected.await() }
                }
                // Production Compose LaunchedEffect resumes on Main after Binder.
                // The old test's instrumentation-thread call hid main-thread /health I/O.
                val resolved = withContext(Dispatchers.Main.immediate) {
                    worker.play(routes.sourceFilm(display.id)!!, 1, "spider-0", true)
                }
                assertTrue("Main-thread caller must reach media without a probe-induced rebuild", resolved is PlaybackResult.Ready)
                resolved as PlaybackResult.Ready
                if (oldUri != null) assertNotEquals("Restored process must issue a fresh capability", oldUri, resolved.media.uri)
                oldUri = resolved.media.uri
                val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", resolved.media.uri)
                ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                    compose.waitUntil(30_000) {
                        var decoded = false
                        scenario.onActivity { activity ->
                            decoded = (activity.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3 &&
                                (activity.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                                PlaybackTransport.options(activity.player, C.TRACK_TYPE_AUDIO).isNotEmpty()
                        }
                        decoded
                    }
                    assertTrue(HerrinPlaybackDiagnosticJournal.export().contains("FIRST_FRAME"))
                }
            }
            routes.clear()
            assertNull(routes.sourceFilm(display.id))
        } finally { worker.close(); cached.delete() }
    }
}
