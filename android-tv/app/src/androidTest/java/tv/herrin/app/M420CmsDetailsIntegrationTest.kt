package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Proves TVBox CMS performs a second, ID-specific detail request before movie playback. */
@RunWith(AndroidJUnit4::class)
class M420CmsDetailsIntegrationTest {
    @Test fun listOnlyNodeLoadsItsOwnEpisodesFromIdsDetailRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"list":[{"vod_id":"my/film:42","vod_name":"自制片清单"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"list":[{"vod_id":"my/film:42","vod_name":"自制片清单",
                  "vod_play_from":"原片","vod_play_url":"第一集${'$'}https://media.example.test/film.mp4"}]}"""))
            val site = TvBoxSite("本地 CMS", needsAdapter = false,
                apiUrl = server.url("/api.php?token=demo%2Ftoken&ac=list").toString())
            val catalog = TvBoxVodRepository().load(site, allowHttp = true) as TvBoxVodResult.Ready
            val film = catalog.films.single()
            assertTrue("CMS catalog often has no episode links", film.playbackEpisodes.isEmpty())
            val detail = TvBoxVodRepository().details(site, film, allowHttp = true)
            assertTrue(detail.toString(), detail is TvBoxVodDetailResult.Ready)
            val episode = (detail as TvBoxVodDetailResult.Ready).film.playbackEpisodes.single()
            assertEquals("第一集", episode.title)
            assertTrue(LicensedPlaybackResolver.resolve(detail.film, 1, "cms-0") is PlaybackResult.Ready)
            val listPath = server.takeRequest(3, TimeUnit.SECONDS)?.path.orEmpty()
            val detailPath = server.takeRequest(3, TimeUnit.SECONDS)?.path.orEmpty()
            assertTrue("Catalog request should use ac=detail&page 1", listPath.contains("pg=1"))
            assertTrue("Detail request must contain opaque ID", detailPath.contains("ids=my%2Ffilm%3A42"))
            assertTrue(detailPath.contains("token=demo%2Ftoken"))
            assertFalse("Signed query token must not be double escaped", detailPath.contains("%252F"))
            assertEquals(2, server.requestCount)
        }
    }
}
