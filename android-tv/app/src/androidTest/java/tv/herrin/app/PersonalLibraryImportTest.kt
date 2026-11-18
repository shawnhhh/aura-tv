package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production manager, scanner, SQLite query and playback mapping together. */
class PersonalLibraryImportTest {
    @Test fun manualHttpImportIndexesVersionsRetainsOfflineHistoryAndRemovesOnlyAfterCompleteRefresh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Preferences(context)
        val savedToken = prefs.tmdbReadToken
        prefs.tmdbReadToken = "" // This fixture never contacts TMDB or another external service.
        val offline = AtomicBoolean(false)
        val version = AtomicInteger(1)
        val library = PersonalLibrary(context)
        var connection: String? = null
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(offline.get()) return MockResponse().setResponseCode(503)
                    if(request.method != "PROPFIND") return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/5000000000").setHeader("Content-Type", "video/mp4").setBody("x")
                    val names = if(version.get() == 1) listOf("Example.2024.1080p.mp4", "Example.2024.2160p.mkv") else listOf("Example.2024.1080p.mp4")
                    val body = names.joinToString("") { name -> "<d:response><d:href>/media/$name</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5000000000</d:getcontentlength><d:getlastmodified>v1</d:getlastmodified></d:prop></d:propstat></d:response>" }
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                }
            }
            server.start()
            try {
                val api = PersonalLibraryApi(context)
                val added = api.handle("/personal/save", JSONObject().put("name", "Controlled DAV").put("protocol", "http")
                    .put("host", "localhost").put("port", server.port).put("path", "media")
                    .put("username", "fixture-user").put("password", "fixture-secret"))
                assertTrue(added.getBoolean("ok")); connection = added.getString("id")
                val before = PersonalLibrary.revisions.value
                api.handle("/personal/folder", JSONObject().put("connection", connection).put("path", "media").put("type", "movie"))
                val folder = library.index.folders(connection).single()
                suspend fun awaitState(state: String) {
                    withTimeout(10000) { while(library.index.folders(connection).single().state != state) delay(25) }
                    library.cancelAndWait()
                }
                awaitState("complete")
                assertTrue(PersonalLibrary.revisions.value > before)
                assertEquals(2, library.index.media(folder.id).length())
                HerrinSmbSessions().use { streams ->
                    val source = library.sources(streams).first { it.id == "personal:$connection" }
                    val offers = source.search(HerrinFilmIdentity(123, "movie", listOf("Example"), 2024))
                    assertEquals(2, offers.size)
                    assertEquals(setOf(1080, 2160), offers.map { it.resolutionClaim }.toSet())
                    val otherId = library.index.folder(requireNotNull(connection), "Other", "video")
                    val other = library.index.folders(connection).first { it.id == otherId }
                    library.index.record(other, PersonalRemoteFile("Other/Example.2024.mp4", false, 10L, "v1"), 1L)
                    library.index.finish(otherId, 1L)
                    assertEquals(2, source.search(HerrinFilmIdentity(123, "movie", listOf("Example"), 2024)).size)
                    val rawId = library.index.media(otherId).getJSONObject(0).getString("id")
                    library.index.correct(rawId, 123, "movie", "Example", 2024)
                    assertEquals(3, source.search(HerrinFilmIdentity(123, "movie", listOf("Example"), 2024)).size)
                    val media = (source.playback(offers.first()) as PlaybackResult.Ready).media
                    assertTrue(media.uri.startsWith(server.url("/media/").toString()))
                    assertFalse(media.uri.contains("fixture-secret")); assertFalse(media.allowHttpRedirects)
                    assertTrue(HerrinPersonalMediaProbe().canRead(media))
                }
                offline.set(true); library.refresh(folder.id); awaitState("offline")
                assertEquals(2, library.index.media(folder.id).length())
                offline.set(false); version.set(2); library.refresh(folder.id); awaitState("complete")
                assertEquals(1, library.index.media(folder.id).length())
                assertFalse(library.index.media(folder.id).toString().contains("fixture-secret"))
            } finally {
                library.cancelAndWait()
                connection?.let { id -> library.index.folders(id).forEach { library.index.removeFolder(it.id) }; library.connections.delete(id) }
                prefs.tmdbReadToken = savedToken
            }
        }
    }
}
