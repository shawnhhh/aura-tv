package tv.herrin.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

class M437MetadataScrapingTest {
    @get:Rule val temp = TemporaryFolder()
    private val token = "original-test-token-0123456789"
    private fun film(title: String = "星光旅人.2024.2160p.WEB-DL.HDR10+.杜比视界.国语中字") = Film(
        "original-film-id", title, "来源副标题", "电影", "", "来源简介", 0,
        artworkUrl = "https://source.example/original.jpg",
        playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(PlaybackLine("line1", "原线路", "private-play-id")))))

    private val search = """{"results":[{"id":437,"title":"星光旅人","original_title":"Starlight Traveler","release_date":"2024-04-01","poster_path":"/original-poster.jpg","backdrop_path":"/original-wide.jpg","overview":"原创测试简介","vote_count":20,"vote_average":8.2,"genre_ids":[878]}]}"""
    private val detail = """{"id":437,"title":"星光旅人","release_date":"2024-04-01","poster_path":"/original-poster.jpg","overview":"原创测试简介","vote_average":8.2,"vote_count":20,"genres":[{"id":878}],"runtime":101,"credits":{"cast":[{"name":"原创演员"}],"crew":[{"name":"原创导演","job":"Director"}]}}"""

    @Test fun `extract title year type and strip unbracketed release flags`() {
        val identity = ArtworkTitle.identify(film())
        assertEquals("星光旅人", identity.title)
        assertEquals("2024", identity.year)
        assertEquals("movie", identity.mediaType)
        assertEquals("剧集测试 第二季", ArtworkTitle.searchQuery("剧集测试 第二季【4K HDR 国语】"))
        assertEquals("1917", ArtworkTitle.searchQuery("1917"))
        assertEquals("2001 太空漫游", ArtworkTitle.searchQuery("2001 太空漫游"))
        assertEquals("Blade Runner 2049", ArtworkTitle.searchQuery("Blade Runner 2049"))
        assertEquals("tv", ArtworkTitle.identify(film("原创剧集").copy(category = "国产剧", episodes = 8)).mediaType)
    }

    @Test fun `real HTTP mock returns official display fields while stream identity stays intact`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(search))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(detail))
            val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, credential ->
                assertEquals("api.themoviedb.org", URI(url).host)
                NetworkArtworkFetcher.load(server.url(URI(url).rawPath + "?" + URI(url).rawQuery).toString(), credential)
            })
            val original = film()
            val enriched = FilmMetadataEnrichment.apply(original, resolver.lookup(original, token)!!)
            assertEquals("星光旅人", enriched.title)
            assertEquals("2024", enriched.year)
            assertEquals(listOf("原创演员"), enriched.actors)
            assertEquals(listOf("原创导演"), enriched.directors)
            assertEquals(101, enriched.runtimeMinutes)
            assertEquals(original.playbackEpisodes, enriched.playbackEpisodes)
            assertEquals(original.id, enriched.id)
            assertEquals(original, FilmMetadataEnrichment.restore(enriched))
            val request = server.takeRequest()
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains(token))
            assertTrue(request.path!!.contains("/search/movie?"))
            assertFalse(request.path!!.contains("2160"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `manual correction persists explicit type and id and directly queries detail`() {
        val cache = temp.newFolder()
        val original = film("错名来源【4K】").copy(category = "未知")
        val first = ArtworkMetadataResolver(cache, ArtworkJsonFetcher { _, _ -> null })
        first.setManualMatch(original, TmdbManualMatch("movie", 437))
        val urls = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(cache, ArtworkJsonFetcher { url, _ -> urls += url; detail })
        assertEquals(TmdbManualMatch("movie", 437), resolver.manualMatch(original))
        val metadata = resolver.lookup(original, token)!!
        assertEquals("星光旅人", metadata.displayTitle)
        assertEquals(listOf("科幻"), metadata.genres)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("/movie/437?"))
        val enriched = FilmMetadataEnrichment.apply(original, metadata)
        assertEquals(TmdbManualMatch("movie", 437), resolver.manualMatch(enriched))
        resolver.setManualMatch(enriched, null)
        assertNull(resolver.manualMatch(original))
        assertNull(resolver.lookup(original, token))
        assertEquals(1, urls.size)
        cache.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains(token)) }
    }

    @Test fun `incremental lookup uses cache and explicit rescrape bypasses fresh cache`() {
        var requests = 0
        val cache = temp.newFolder()
        val resolver = ArtworkMetadataResolver(cache, ArtworkJsonFetcher { url, _ ->
            requests++; if (url.contains("/search/")) search else detail
        })
        resolver.lookup(film(), token)
        resolver.lookup(film(), token)
        assertEquals(2, requests)
        resolver.lookup(film(), token, forceRefresh = true)
        assertEquals(4, requests)
        resolver.lookup(film("不同影片"), token)
        assertEquals(5, requests) // Only the new title is searched; no warehouse or Spider work.
        cache.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains(token)) }
    }

    @Test fun `transient network failure does not poison a later successful scrape`() {
        var requests = 0
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            requests++
            if (requests == 1) null else if (url.contains("/search/")) search else detail
        })
        assertNull(resolver.lookup(film(), token))
        assertNotNull(resolver.lookup(film(), token))
        assertEquals(3, requests)
    }

    @Test fun `same title remake with unknown year remains source artwork and manual wrong id rejected`() {
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { _, _ ->
            """{"results":[{"id":1,"title":"同名影片","release_date":"2024-01-01","poster_path":"/first.jpg"},{"id":2,"title":"同名影片","release_date":"2000-01-01","poster_path":"/second.jpg"}]}"""
        })
        val original = film("同名影片")
        assertNull(resolver.lookup(original, token))
        assertEquals("https://source.example/original.jpg", original.artworkUrl)
        resolver.setManualMatch(original, TmdbManualMatch("movie", 99))
        assertNull(resolver.lookup(original, token, forceRefresh = true))
    }
}
