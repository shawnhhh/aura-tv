package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.util.concurrent.atomic.AtomicReference

/** Imported SQLite file -> actual My card -> production player, with real controlled MP4 bytes. */
class PersonalLibraryDirectPlaybackTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private val expectedAuth = AtomicReference(Credentials.basic("fixture-user", "first-password"))
    private lateinit var library: PersonalLibrary
    private lateinit var connection: String
    private lateinit var folder: String
    private lateinit var file: String
    private val readAuth = java.util.concurrent.CopyOnWriteArrayList<String?>()

    private fun save(password: String, id: String? = null): JSONObject = PersonalLibraryApi(inst.targetContext)
        .handle("/personal/save", JSONObject().put("name", "受控个人片库").put("protocol", "http")
            .put("host", "localhost").put("port", server.port).put("path", "media")
            .put("username", "fixture-user").put("password", password).apply { id?.let { put("id", it) } })

    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() = runBlocking {
            val context = inst.targetContext
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
            context.getSharedPreferences("herrin-watch-history", 0).edit().clear().commit()
            HerrinPlaybackDiagnosticJournal.clear()
            val video = inst.context.assets.open("playback-diagnostics/baseline-72.mp4").use { it.readBytes() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val authorized = request.getHeader("Authorization") == expectedAuth.get()
                    if (request.method == "GET") readAuth += request.getHeader("Authorization")
                    if (!authorized) return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=fixture")
                    if (request.method == "PROPFIND") return MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml").setBody("""<d:multistatus xmlns:d="DAV:"><d:response>
                        <d:href>/media/Local.Movie.2026.1080p.mp4</d:href><d:propstat><d:prop><d:resourcetype/>
                        <d:getcontentlength>${video.size}</d:getcontentlength><d:getlastmodified>unchanged</d:getlastmodified>
                        </d:prop></d:propstat></d:response></d:multistatus>""")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (start >= video.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${video.size}")
                    val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: video.lastIndex).coerceAtMost(video.lastIndex)
                    return MockResponse().setResponseCode(if (range == null) 200 else 206)
                        .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${video.size}") }
                        .setBody(Buffer().write(video, start, end - start + 1))
                }
            }
            server.start()
            library = PersonalLibrary(context)
            val added = save("first-password")
            assertTrue(added.toString(), added.getBoolean("ok"))
            connection = added.getString("id")
            assertTrue(PersonalLibrary.awaitCurrentScan())
            folder = library.index.folders(connection).single().id
            file = library.index.media(folder).getJSONObject(0).getString("id")
        }
        override fun after() {
            library.cancelAndWait()
            library.index.folders(connection).forEach { library.index.removeFolder(it.id) }
            library.connections.delete(connection)
            server.shutdown()
            inst.targetContext.getSharedPreferences("herrin", 0).edit().clear().commit()
            inst.targetContext.getSharedPreferences("herrin-watch-history", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun frameCount() = HerrinPlaybackDiagnosticJournal.export().lineSequence().count { "CLICK_TO_FIRST_FRAME" in it }
    private fun position(): Int {
        val label = compose.onNodeWithTag("player-position-label").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        return label.split(':').fold(0) { value, part -> value * 60 + part.toInt() }
    }

    @Test fun importedFilePlaysFromMySurvivesScanSeeksAndReopensWithUpdatedCredentials() = runBlocking {
        compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess }
        compose.onNodeWithTag("nav-我的").performClick()
        val card = "card-personal-index:$file"
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(card).assertExists() }.isSuccess }
        val initialFrames = frameCount()
        compose.onNodeWithTag(card).performScrollTo().performClick()
        compose.waitUntil(30000) { frameCount() > initialFrames }
        compose.onNodeWithTag("player-root-focus").assertIsDisplayed()
        compose.onNodeWithTag("spider-authorization-dialog").assertDoesNotExist()
        compose.onNodeWithTag("player-refresh-source").assertDoesNotExist()
        assertTrue(PersonalLibrary.awaitCurrentScan())
        library.refresh(folder)
        assertTrue(PersonalLibrary.awaitCurrentScan())
        compose.onNodeWithTag("player-root-focus").assertIsDisplayed()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("transport-toggle").performClick()
        compose.onNodeWithTag("transport-toggle").assertContentDescriptionEquals("播放")
        val settled = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(2500) { android.os.SystemClock.elapsedRealtime() - settled >= 800 }
        val paused = position()
        val observed = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(2500) { android.os.SystemClock.elapsedRealtime() - observed >= 1000 }
        assertEquals("The actual paused position must remain stable", paused, position())
        compose.onNodeWithTag("transport-forward").performClick()
        compose.waitUntil(5000) { runCatching { position() == paused + 10 }.getOrDefault(false) }
        compose.onNodeWithTag("transport-toggle").performClick()
        compose.onNodeWithTag("transport-toggle").assertContentDescriptionEquals("暂停")
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching {
            compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
            compose.onNodeWithTag("film-detail-list").assertExists()
            compose.onNodeWithTag("detail-favorite").assertIsFocused()
        }.isSuccess }
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching {
            compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
            compose.onNodeWithTag(card).assertIsDisplayed()
        }.isSuccess }
        expectedAuth.set(Credentials.basic("fixture-user", "updated-password"))
        assertTrue(save("updated-password", connection).getBoolean("ok"))
        assertTrue(PersonalLibrary.awaitCurrentScan())
        val beforeReplay = frameCount()
        readAuth.clear()
        compose.onNodeWithTag(card).performScrollTo().performClick()
        compose.waitUntil(30000) { frameCount() > beforeReplay }
        compose.onNodeWithTag("spider-authorization-dialog").assertDoesNotExist()
        assertTrue(readAuth.isNotEmpty())
        assertTrue("Every new media request must use the current saved credential", readAuth.all { it == expectedAuth.get() })
    }
}
