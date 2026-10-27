package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TvBoxVodIntegrationTest {
    @Test fun standardCmsJsonApiLoadsWithoutExecutingSpiderCode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = server.url("/provide/vod/").toString()
            val site = TvBoxSite("演示 CMS", false, api)
            assertTrue(TvBoxVodRepository().load(site, false) is TvBoxVodResult.Error)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"list":[{"vod_id":"1","vod_name":"授权影片","type_name":"电影"}]}"""))
            val found = TvBoxVodRepository().load(site, true) as TvBoxVodResult.Ready
            assertEquals("授权影片", found.films.single().title)
            assertTrue(server.takeRequest().path.orEmpty().contains("ac=detail"))
        }
    }
}
