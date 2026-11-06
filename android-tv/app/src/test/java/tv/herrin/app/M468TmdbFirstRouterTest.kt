package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M468TmdbFirstRouterTest {
    private fun item(title: String, year: String, source: String) =
        Film("item-" + source, title, "", "电影", year, "", R.drawable.photofilm_orbit)

    private fun source(id: String, type: HerrinSourceType, options: List<HerrinSourceMovie>,
                       calls: MutableList<String>): HerrinFilmSource =
        object : HerrinFilmSource {
            override val id = id
            override val type = type
            override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
                calls += id
                return options
            }
        }

    private val interstellar = HerrinFilmIdentity(157336, "movie",
        listOf("星际穿越", "Interstellar"), 2014)

    @Test fun personalMediaWinsEvenWhenTvboxClaimsHigherResolution() = runBlocking {
        val calls = mutableListOf<String>()
        val local = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            item("Interstellar", "2014", "nas"), "movie", resolutionClaim = 1080)
        val remote = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            item("星际穿越", "2014", "cms"), "movie", resolutionClaim = 2160)
        val result = HerrinFilmRouter(listOf(
            source("nas", HerrinSourceType.NAS, listOf(local), calls),
            source("cms", HerrinSourceType.TVBOX_CMS, listOf(remote), calls)
        )).find(interstellar)
        assertEquals(HerrinSourceTier.PERSONAL, result.selectedTier)
        assertEquals(listOf("nas"), calls)
        assertEquals(local, result.options.single())
    }

    @Test fun tvboxFallbackOnlyWhenNoMatchingNasOrCloudMedia() = runBlocking {
        val calls = mutableListOf<String>()
        val mismatched = HerrinSourceMovie("cloud", HerrinSourceType.CLOUD,
            item("星际穿越", "2019", "cloud"), "movie")
        val usable = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
            item("Interstellar", "2014", "cms"), "movie")
        val result = HerrinFilmRouter(listOf(
            source("cloud", HerrinSourceType.CLOUD, listOf(mismatched), calls),
            source("cms", HerrinSourceType.TVBOX_CMS, listOf(usable), calls)
        )).find(interstellar)
        assertEquals(listOf("cloud", "cms"), calls)
        assertEquals(HerrinSourceTier.TVBOX, result.selectedTier)
        assertEquals(usable, result.options.single())
    }

    @Test fun personalRankPrefers4kWhileRejectingWrongTvSeasonAndRemake() = runBlocking {
        val calls = mutableListOf<String>()
        val hd = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            item("Interstellar", "2014", "a"), "movie", resolutionClaim = 1080)
        val uhd = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            item("星际穿越", "2014", "b"), "movie", resolutionClaim = 2160)
        val mismatch = HerrinSourceMovie("nas", HerrinSourceType.NAS,
            item("Interstellar", "1994", "c"), "movie", resolutionClaim = 2160)
        val response = HerrinFilmRouter(listOf(source("nas", HerrinSourceType.NAS,
            listOf(hd, mismatch, uhd), calls))).find(interstellar)
        assertEquals(listOf(uhd, hd), response.options)
        assertEquals(2160, HerrinFilmIdentityMatch.resolutionClaim("Movie.2160p.HDR"))
    }

    @Test fun liveChannelsChooseDirectM3uAndKeepTvboxAsSelectableBackup() {
        val first = LiveChannel("CCTV 1 HD", "https://iptv.example.org/one", "央视频道")
        val backup = LiveChannel("CCTV 1", "https://tvbox.example.org/one", "央视频道")
        val other = LiveChannel("CCTV 5", "https://tvbox.example.org/five", "央视频道")
        val combined = HerrinLivePriority.combine(listOf(first), listOf(backup, other, backup))
        assertEquals(listOf(first, backup, other), combined)
        assertEquals(listOf(first, backup), HerrinLivePriority.alternatives(first, combined))
    }

    @Test fun tmdbCatalogParsesStableIdsRealFieldsAndRejectsInvalidImages() {
        val fetcher = ArtworkJsonFetcher { _, _ -> null }
        val parser = HerrinTmdbDiscovery(fetcher)
        val films = parser.parsePage("""{"results":[
            {"id":157336,"title":"星际穿越","original_title":"Interstellar",
             "release_date":"2014-11-05","genre_ids":[878,18],"vote_average":8.6,
             "vote_count":30000,"poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg",
             "overview":"在宇宙中探索"},
            {"id":9,"title":"Invalid","poster_path":"https://attacker.example/x.jpg","adult":true},
            {"id":157336,"title":"Duplicate"}
        ]}""", "movie")
        assertEquals(1, films.size)
        val film = films.single()
        assertEquals("tmdb-movie-157336", film.id)
        assertEquals("星际穿越", film.title)
        assertEquals("TMDB", film.ratingProvider)
        assertEquals(8.6, film.ratingValue!!, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", film.artworkUrl)
        assertEquals("科幻", film.genres.first())
        assertEquals("movie" to 157336, parser.identity(film))
        assertNull(film.cmsVodId)
        assertNull(film.spiderVodId)
        assertTrue(film.playbackEpisodes.isEmpty())
    }

    @Test fun top250IsExplicitlyTmdbTopRatedNotImdbAndDoesNotFetchOnInit() {
        val requested = mutableListOf<String>()
        val parser = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            requested += url
            """{"results":[]}"""
        })
        assertEquals("TMDB 高分电影 Top 250", parser.sections.first { it.key == "top250" }.title)
        assertTrue(requested.isEmpty())
        assertEquals(12, parser.sections.size)
        assertTrue(parser.sections.any { it.key == "variety" })
    }
}
