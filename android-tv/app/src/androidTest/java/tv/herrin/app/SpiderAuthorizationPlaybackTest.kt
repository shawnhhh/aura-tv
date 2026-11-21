package tv.herrin.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic platform only: real IPC, callback continuation, parsing and Media3 decoding. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SpiderAuthorizationPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class FixtureContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean =
            super.bindService(Intent(this, SpiderAuthorizationFixtureService::class.java), conn, flags)
    }
    @Test fun grantedSessionContinuesParsingAndVideoReusesThenReauthorizesAfterExpiry() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val authorized = AtomicBoolean()
        val grants = AtomicInteger()
        val resolves = AtomicInteger()
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/session" -> MockResponse().setBody("""{"authorized":${authorized.get()}}""")
                "/grant" -> { grants.incrementAndGet(); authorized.set(true)
                    MockResponse().setBody("""{"authorized":true,"token":"original-private-session"}""") }
                "/resolve" -> { resolves.incrementAndGet()
                    MockResponse().setBody("""{"parse":0,"url":"${server.url("/video").toString().replace("localhost","127.0.0.1")}"}""") }
                "/video" -> { reads.incrementAndGet(); MockResponse().setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(inst.context.assets.open("synthetic.mp4").use { it.readBytes() })) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val bridge = TvBoxSpiderBridge(FixtureContext(inst.targetContext))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var media by mutableStateOf<PlaybackMedia?>(null)
        var player: ExoPlayer? = null
        compose.setContent { MaterialTheme {
            media?.let { AuthorizedPlaybackPanel(media = it, onPlayer = { active -> player = active }, onBack = { media = null }) }
            SpiderAuthorizationHost()
        } }
        try {
            val site = TvBoxSite("Original mock platform", true, key = "auth-playback-fixture",
                spiderApi = "csp_OriginalFixture", extension = server.url("/").toString().removeSuffix("/").replace("localhost","127.0.0.1"))
            val browse = withTimeout(15000) { bridge.browse(site, TvBoxConfig.Single(listOf(site), emptyList(), true)) }
            assertTrue(browse is SpiderResult.Ready)
            val film = (browse as SpiderResult.Ready<List<Film>>).value.single()
            suspend fun play(expectAuthorization: Boolean): PlaybackMedia {
                val pending = scope.async { bridge.play(film, 1, "original-line", true) }
                if (expectAuthorization) {
                    compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("spider-authorization-qr").assertIsDisplayed() }.isSuccess }
                    assertFalse("parse must wait for the real mock grant", pending.isCompleted)
                    compose.onNodeWithTag("spider-authorization-action-0").performClick()
                }
                val ready = withTimeout(10000) { pending.await() }
                assertTrue(ready is PlaybackResult.Ready)
                compose.waitUntil(5000) { SpiderAuthorizationUi.requests.value == null }
                return (ready as PlaybackResult.Ready).media
            }
            val parsed = play(true)
            assertEquals(1, grants.get()); assertEquals(1, resolves.get())
            compose.runOnUiThread { media = parsed }
            compose.waitUntil(20000) {
                var frames = 0
                compose.runOnUiThread { frames = player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                frames > 3
            }
            assertTrue("parsed media must actually be read and decoded", reads.get() > 0)
            play(false)
            assertEquals("valid source-owned session must not prompt again", 1, grants.get())
            assertEquals(2, resolves.get())
            authorized.set(false)
            play(true)
            assertEquals(2, grants.get()); assertEquals(3, resolves.get())
            assertNull(SpiderAuthorizationUi.requests.value)
        } finally {
            compose.runOnUiThread { media = null }
            bridge.close(); scope.cancel(); server.shutdown()
        }
    }
}
