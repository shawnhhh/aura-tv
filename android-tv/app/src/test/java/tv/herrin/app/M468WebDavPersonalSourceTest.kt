package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class M468WebDavPersonalSourceTest {
    @Test fun webdavNasFinds4kMovieWithoutEverAskingTvbox() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "PROPFIND") return MockResponse().setResponseCode(405)
                    val xml = if (request.path == "/dav/") """
                      <d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                        <d:response><d:href>/dav/movies/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                      </d:multistatus>
                    """.trimIndent() else """
                      <d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/dav/movies/Interstellar.2014.2160p.HDR.mkv</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
                        <d:response><d:href>https://different.example.org/secret.mkv</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
                      </d:multistatus>
                    """.trimIndent()
                    return MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml").setBody(xml)
                }
            }
            server.start()
            val source = HerrinWebDavSource(HerrinWebDavAccount("My NAS",
                server.url("/dav/").toString(), username = "demo", password = "fake-pass",
                allowInsecureHttp = true))
            val calls = mutableListOf<String>()
            val fallback = object : HerrinFilmSource {
                override val id = "tvbox"
                override val type = HerrinSourceType.TVBOX_CMS
                override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
                    calls += "tvbox"
                    return emptyList()
                }
            }
            val result = HerrinFilmRouter(listOf(source, fallback)).find(
                HerrinFilmIdentity(157336, "movie", listOf("Interstellar"), 2014))
            assertEquals(HerrinSourceTier.PERSONAL, result.selectedTier)
            assertEquals(1, result.options.size)
            assertEquals(2160, result.options.single().resolutionClaim)
            assertTrue(result.options.single().hdrClaim)
            assertTrue(calls.isEmpty())
            val playback = source.playback(result.options.single())
            assertTrue(playback is PlaybackResult.Ready)
            val media = (playback as PlaybackResult.Ready).media
            assertTrue(media.uri.contains("Interstellar.2014.2160p.HDR.mkv"))
            assertEquals(false, media.allowHttpRedirects)
            assertTrue(media.headers.containsKey("Authorization"))
            assertEquals(2, server.requestCount)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("1", server.takeRequest().getHeader("Depth"))
        }
    }

    @Test fun pastedWebdavFolderWithoutSlashResolvesRelativeMovieHrefSafely() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>Interstellar.2014.1080p.mp4</d:href>
                    <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent()))
            server.start()
            val source = HerrinWebDavSource(HerrinWebDavAccount(
                "Local", server.url("/dav").toString(), allowInsecureHttp = true))
            val found = source.search(HerrinFilmIdentity(157336, "movie",
                listOf("Interstellar"), 2014))
            assertEquals(1, found.size)
            assertEquals("/dav/", server.takeRequest().path)
            val playback = source.playback(found.single()) as PlaybackResult.Ready
            assertTrue(playback.media.uri.endsWith("/dav/Interstellar.2014.1080p.mp4"))
        }
    }

    @Test fun webdavRequiresExplicitInsecureHttpApproval() {
        MockWebServer().use { server ->
            server.start()
            assertThrows(IllegalArgumentException::class.java) {
                HerrinWebDavSource(HerrinWebDavAccount("My NAS", server.url("/dav/").toString()))
            }
        }
    }
}
