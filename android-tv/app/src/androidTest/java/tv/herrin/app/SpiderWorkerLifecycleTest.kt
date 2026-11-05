package tv.herrin.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.github.catvod.Proxy
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real original DEX/Init/Spider + production worker, with explicit latch ordering. */
class SpiderWorkerLifecycleTest {
    @Test fun approvedJarSharesItsLoaderAcrossSitesButNeverTheirFilmAuthority() = runBlocking {
        Fixture("unused").use { fixture ->
            val worker = TvBoxSpiderWorker(fixture.context)
            try {
                val first = worker.browse(fixture.site, fixture.config) as SpiderResult.Ready
                val film = (worker.details(first.value.single()) as SpiderResult.Ready).value
                val loader = field(worker, "activeDexLoader")
                val other = fixture.site.copy(key = "second-approved-site")
                val otherCatalog = worker.browse(other, fixture.config) as SpiderResult.Ready
                assertSame("JarLoader caches by approved JAR, not site", loader, field(worker, "activeDexLoader"))
                assertEquals("Init runs once per authorized JAR", 1, fixture.count("bootstraps"))
                assertEquals("Sites still own distinct initialized Spiders", 2, fixture.count("inits"))
                assertTrue(worker.details(film) is SpiderResult.Failure)
                assertTrue(worker.play(film, 1, "spider-0", true) is PlaybackResult.Unavailable)
                val otherFilm = (worker.details(otherCatalog.value.single()) as SpiderResult.Ready).value
                val staticMedia = worker.play(otherFilm, 2, "spider-0", true) as PlaybackResult.Ready
                val http = java.net.URL(staticMedia.media.uri).openConnection() as java.net.HttpURLConnection
                try {
                    http.connectTimeout = 3_000; http.readTimeout = 3_000
                    assertEquals("Cached Init URL must reach current JAR static handler", 200, http.responseCode)
                    assertEquals("en", http.getHeaderField("Content-Language"))
                    assertArrayEquals(InstrumentationRegistry.getInstrumentation().context.assets
                        .open("synthetic.mp4").use { it.readBytes() }, http.inputStream.use { it.readBytes() })
                } finally { http.disconnect() }
                assertTrue(worker.browse(fixture.site, fixture.config) is SpiderResult.Ready)
                assertTrue(worker.details(SpiderIpc.readFilm(SpiderIpc.film(film))) is SpiderResult.Ready)
                val forged = film.copy(playbackEpisodes = listOf(film.playbackEpisodes.first().copy(
                    lines = listOf(film.playbackEpisodes.first().lines.first().copy(uri = "other-film")))))
                assertTrue(worker.play(forged, 1, "spider-0", true) is PlaybackResult.Unavailable)
                worker.close()
                assertTrue(worker.browse(fixture.site, fixture.config) is SpiderResult.Ready)
                assertNotSame(loader, field(worker, "activeDexLoader"))
                assertTrue("Old approval cannot survive revocation", worker.details(film) is SpiderResult.Failure)
            } finally { worker.close() }
        }
    }
    @Test fun closedWorkerRejectsLateSpiderInitAndFreshApplyGetsNewResources() = runBlocking {
        Fixture("init").use { fixture ->
            val worker = TvBoxSpiderWorker(fixture.context)
            val pending = async(Dispatchers.Default) { worker.browse(fixture.site, fixture.config) }
            try {
                fixture.awaitEntered()
                val oldLoader = fixture.state["init.instance"]!!.javaClass.classLoader
                assertTrue(oldLoader is DexClassLoader)
                val oldPort = Proxy.getPort()
                assertTrue("Proxy is available before Spider.init", listening(oldPort))
                worker.close()
                worker.close()
                fixture.release()
                val result = withTimeout(10_000) { pending.await() }
                assertTrue("Late init must be rejected: $result", result is SpiderResult.Failure)
                assertReleased(worker)
                assertEquals(1, fixture.count("destroys"))
                assertEquals("Revoked init must not invoke home", 0, fixture.count("homes"))
                assertFalse("Temporary proxy must close", listening(oldPort))
                assertTrue(worker.search(fixture.site, "fixture") is SpiderResult.Failure)
                assertEquals(0, fixture.count("searches"))

                val fresh = fixture.site.copy(key = "fresh-authorized-fixture")
                assertTrue(worker.browse(fresh, fixture.config) is SpiderResult.Ready)
                assertNotSame("Fresh apply cannot reuse retired DEX loader", oldLoader,
                    field(worker, "activeDexLoader"))
                val freshPort = Proxy.getPort()
                assertTrue(listening(freshPort))
                assertTrue(worker.browse(fresh, fixture.config) is SpiderResult.Ready)
                assertEquals("Same valid session reuses Spider", 2, fixture.count("inits"))
                assertTrue(worker.search(fresh, "fixture") is SpiderResult.Ready)
                assertTrue(worker.search(fixture.site, "fixture") is SpiderResult.Failure)
                assertEquals(1, fixture.count("searches"))
                worker.close()
                worker.close()
                assertReleased(worker)
                assertEquals("Each published/temporary Spider destroyed once", 2, fixture.count("destroys"))
                assertFalse(listening(freshPort))
            } finally {
                fixture.release()
                pending.cancel()
                pending.join()
                worker.close()
            }
        }
    }

    @Test fun retiredInitCannotReplaceOrClearANewerAuthorizedSite() = runBlocking {
        Fixture("init").use { fixture ->
            val worker = TvBoxSpiderWorker(fixture.context)
            val old = async(Dispatchers.Default) { worker.browse(fixture.site, fixture.config) }
            try {
                fixture.awaitEntered()
                val retiredLoader = fixture.state["init.instance"]!!.javaClass.classLoader
                val retiredPort = Proxy.getPort()
                worker.close()
                // Check before creating a new listener: the OS may legitimately
                // assign the same ephemeral port to the fresh proxy.
                assertFalse(listening(retiredPort))
                fixture.disableGateForNewInstances()
                val fresh = fixture.site.copy(key = "new-authorized-site")
                assertTrue(worker.browse(fresh, fixture.config) is SpiderResult.Ready)
                val currentLoader = field(worker, "activeDexLoader")
                val currentPort = Proxy.getPort()
                assertNotSame(retiredLoader, currentLoader)
                fixture.release()
                assertTrue("Retired init must fail",
                    withTimeout(10_000) { old.await() } is SpiderResult.Failure)
                assertSame("Retired completion cannot overwrite loader", currentLoader,
                    field(worker, "activeDexLoader"))
                assertEquals("Retired cleanup cannot clear new proxy", currentPort, Proxy.getPort())
                assertTrue(listening(currentPort))
                assertEquals(1, fixture.count("destroys"))
                assertTrue(worker.search(fresh, "fixture") is SpiderResult.Ready)
                assertTrue(worker.search(fixture.site, "fixture") is SpiderResult.Failure)
            } finally {
                fixture.release()
                old.cancel()
                old.join()
                worker.close()
            }
        }
    }

    @Test fun closeDuringJarBootstrapCannotConstructOrPublishASpider() = runBlocking {
        Fixture("bootstrap").use { fixture ->
            val worker = TvBoxSpiderWorker(fixture.context)
            val pending = async(Dispatchers.Default) { worker.browse(fixture.site, fixture.config) }
            try {
                fixture.awaitEntered()
                val oldPort = Proxy.getPort()
                assertTrue(listening(oldPort))
                worker.close()
                fixture.release()
                assertTrue("Closed bootstrap must not succeed",
                    withTimeout(10_000) { pending.await() } is SpiderResult.Failure)
                assertReleased(worker)
                assertEquals(0, fixture.count("inits"))
                assertEquals(0, fixture.count("homes"))
                assertFalse(listening(oldPort))
            } finally {
                fixture.release()
                pending.cancel()
                pending.join()
                worker.close()
            }
        }
    }

    @Test fun cancelledInitIgnoringInterruptionCannotSurviveClose() = runBlocking {
        Fixture("init").use { fixture ->
            val worker = TvBoxSpiderWorker(fixture.context)
            val pending = async(Dispatchers.Default) { worker.browse(fixture.site, fixture.config) }
            try {
                fixture.awaitEntered()
                val oldPort = Proxy.getPort()
                pending.cancel()
                worker.close()
                fixture.release()
                withTimeout(10_000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertReleased(worker)
                assertEquals(1, fixture.count("destroys"))
                assertEquals(0, fixture.count("homes"))
                assertFalse(listening(oldPort))
            } finally {
                fixture.release()
                pending.cancel()
                pending.join()
                worker.close()
            }
        }
    }

    private fun assertReleased(worker: TvBoxSpiderWorker) {
        for (name in listOf("active", "activeKey", "activeSiteKey", "activeDexLoader",
                "activeNativeProxy", "activeHttpHost", "activeScriptRuntime"))
            assertNull("Worker retained $name after close", field(worker, name))
        assertEquals(-1, Proxy.getPort())
    }

    private fun field(worker: TvBoxSpiderWorker, name: String): Any? =
        worker.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(worker)

    private fun listening(port: Int): Boolean = port > 0 && runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
    }.isSuccess

    private class Fixture(private val stage: String) : AutoCloseable {
        private val inst = InstrumentationRegistry.getInstrumentation()
        val state = ConcurrentHashMap<String, Any>().apply {
            for (key in listOf("inits", "destroys", "homes", "searches", "bootstraps")) put(key, AtomicInteger())
            put("$stage.entered", CountDownLatch(1))
            put("$stage.proceed", CountDownLatch(1))
        }
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getSystemService(name: String): Any? =
                if (name == "herrin.fixture.lifecycle") state else super.getSystemService(name)
        }
        private val jar = inst.context.assets.open("m467-spider.jar").use { it.readBytes() }
        private val md5 = MessageDigest.getInstance("MD5").digest(jar).joinToString("") { "%02x".format(it) }
        private val cached = File(File(context.codeCacheDir, "herrin-spider").apply { mkdirs() }, "$md5.jar")
        val site = TvBoxSite("Original lifecycle fixture", needsAdapter = true,
            key = "original-lifecycle-fixture", spiderApi = "csp_Fixture")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true,
            spiderJar = "https://example.invalid/original-fixture.jar;md5;$md5")

        init {
            if (cached.exists()) check(cached.delete())
            cached.writeBytes(jar)
            check(cached.setReadOnly())
        }
        fun awaitEntered() = assertTrue("DEX fixture must reach $stage gate",
            (state["$stage.entered"] as CountDownLatch).await(15, TimeUnit.SECONDS))
        fun release() = (state["$stage.proceed"] as CountDownLatch).countDown()
        fun disableGateForNewInstances() { state.remove("$stage.entered") }
        fun count(key: String) = (state[key] as AtomicInteger).get()
        override fun close() { release(); cached.delete() }
    }
}
