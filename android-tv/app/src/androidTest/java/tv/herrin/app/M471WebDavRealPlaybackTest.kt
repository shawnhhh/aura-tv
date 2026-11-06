package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real local HTTP + original MP4 decoder acceptance, not an isolated router mock:
 * WebDAV PROPFIND -> strict Range probe -> personal episode/line ->
 * same Media3 player surface used by Herrin.
 *
 * No third-party movie source, remote Spider, or private credentials.
 */
@RunWith(AndroidJUnit4::class)
class M471WebDavRealPlaybackTest {
    @Test fun discoveredNasVideoSurvivesRangeProbeAndDecodesMedia3Frames() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val video = instrument.context.assets.open("synthetic.mp4").use { it.readBytes() }
        val requiredAuth = Credentials.basic("fixture-user", "fixture-password")
        val events = CopyOnWriteArrayList<Pair<String, String?>>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    events += (request.method.orEmpty() to request.getHeader("Range"))
                    if (request.getHeader("Authorization") != requiredAuth)
                        return MockResponse().setResponseCode(401)
                    return when {
                        request.method == "PROPFIND" && request.path == "/dav/" ->
                            MockResponse().setResponseCode(207)
                                .setHeader("Content-Type", "application/xml")
                                .setBody("""<d:multistatus xmlns:d="DAV:"><d:response>
                                  <d:href>/dav/Interstellar.2014.1080p.mp4</d:href>
                                  <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
                                  </d:response></d:multistatus>""")
                        request.method == "GET" &&
                            request.path == "/dav/Interstellar.2014.1080p.mp4" &&
                            request.getHeader("Range") == "bytes=0-0" ->
                            MockResponse().setResponseCode(206)
                                .setHeader("Content-Type", "video/mp4")
                                .setHeader("Accept-Ranges", "bytes")
                                .setHeader("Content-Range", "bytes 0-0/${video.size}")
                                .setBody(Buffer().write(video, 0, 1))
                        request.method == "GET" &&
                            request.path == "/dav/Interstellar.2014.1080p.mp4" ->
                            MockResponse().setResponseCode(200)
                                .setHeader("Content-Type", "video/mp4")
                                .setHeader("Accept-Ranges", "bytes")
                                .setBody(Buffer().write(video))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val source = HerrinWebDavSource(HerrinWebDavAccount(
                "Licensed NAS", server.url("/dav/").toString(),
                username = "fixture-user", password = "fixture-password",
                allowInsecureHttp = true))
            val probe = HerrinPersonalMediaProbe()
            val target = HerrinFilmIdentity(157336, "movie", listOf("Interstellar"), 2014)
            // Separate URL discovery, authorization mapping and HTTP Range checks
            // so the Android CI report identifies the actual failing stage.
            val discovered = source.search(target)
            assertTrue("WebDAV PROPFIND returned no matching files; requests=" + events.toList(),
                discovered.isNotEmpty())
            val direct = source.playback(discovered.first()) as? PlaybackResult.Ready
            assertNotNull("WebDAV playback mapping rejected discovered file", direct)
            val probeResult = probe.canRead(direct!!.media)
            assertTrue("WebDAV Range check rejected authorized video; requests=" + events.toList(),
                probeResult)
            val chosen = HerrinVerifiedSourceResolver(listOf(source)).find(
                target,
                personalPlayback = { source.playback(it) },
                verifyPersonal = { probe.canRead(it) },
                remoteDetails = { null })
            assertEquals(HerrinSourceTier.PERSONAL, chosen.lookup.selectedTier)
            val playlist = chosen.personalPlaylist ?: failAndReturn()
            val episode = playlist.episodes.single()
            val line = episode.lines.single()
            assertEquals(1 to line.id,
                HerrinPlaybackEntry.firstPlayable(
                    chosen.lookup.options.single().film.copy(playbackEpisodes = playlist.episodes)))
            val media = playlist.mediaByLine.getValue(line.id)
            assertTrue(media.uri.endsWith("Interstellar.2014.1080p.mp4"))
            assertEquals("The source's own Authorization header must be preserved",
                requiredAuth, media.headers["Authorization"])

            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(instrument.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", media.uri)
                    .putExtra("fixtureAuthorization", media.headers["Authorization"])
            ).use { scenario ->
                var frames = 0
                var failure: String? = null
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline && frames < 4 && failure == null) {
                    scenario.onActivity {
                        runCatching {
                            frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                            failure = it.player.playerError?.errorCodeName
                        }
                    }
                    if (frames < 4) Thread.sleep(90)
                }
                assertNull("Verified WebDAV local MP4 must not raise decoder error", failure)
                assertTrue("Verified WebDAV should actually render real Media3 frames", frames >= 4)
            }
            assertTrue("A DAV listing must precede a Range probe",
                events.any { it.first == "PROPFIND" })
            assertTrue("Must probe the original file with a one-byte Range GET",
                events.any { it.first == "GET" && it.second == "bytes=0-0" })
            assertTrue("The player must fetch actual MP4 bytes after verification",
                events.count { it.first == "GET" } >= 2)
        }
    }

    private fun failAndReturn(): HerrinPersonalPlaylist =
        throw AssertionError("Verified NAS must return a real playable playlist")
}
