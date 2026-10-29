package tv.herrin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilmFacetFiltersTest {
    @get:Rule val temp = TemporaryFolder()
    private val token = "abcDEF1234567890abcDEF1234567890"

    private fun source(id: String, year: String) = Film(
        id = id, title = "同名作品", subtitle = "源目录", category = "电影", year = year,
        synopsis = "原始介绍", artwork = R.drawable.photofilm_orbit,
        cmsVodId = "cms-$id", playbackEpisodes = listOf(PlaybackEpisode(1, "第1集",
            listOf(PlaybackLine("source", "首选线路", "https://licensed.example.test/1.m3u8")))))

    @Test fun `actor and year filters intersect and clearing restores all`() {
        val films = listOf(
            source("a", "2024").copy(actors = listOf("张演员"), regions = listOf("中国大陆")),
            source("b", "2025").copy(actors = listOf("张演员"), regions = listOf("美国")),
            source("c", "2025").copy(actors = listOf("李演员"), regions = listOf("日本"))
        )
        val combined = FilmFacetSelection(actor = "张演员", year = "2025")
        assertEquals(listOf("b"), FilmFacetFilters.results(films, combined).map { it.id })
        assertEquals(listOf("a", "b"), FilmFacetFilters.results(films,
            combined.copy(year = null)).map { it.id })
        assertEquals(3, FilmFacetFilters.results(films, FilmFacetSelection()).size)
        assertTrue(FilmFacetSelection().isEmpty)
    }

    @Test fun `TMDB rating bands combine with year and do not count fake or missing scores`() {
        val accepted = source("a", "2025").copy(ratingValue = 8.3, ratingProvider = "TMDB")
        val wrongYear = source("b", "2024").copy(ratingValue = 9.3, ratingProvider = "TMDB")
        val spoofed = source("c", "2025").copy(ratingValue = 9.9, ratingProvider = "影视源")
        val absent = source("d", "2025")
        val films = listOf(accepted, wrongYear, spoofed, absent)
        assertEquals(listOf("a"), FilmFacetFilters.results(films,
            FilmFacetSelection(year = "2025", ratingBand = "≥8.0")).map { it.id })
        assertEquals(listOf("c", "d"), FilmFacetFilters.results(films,
            FilmFacetSelection(year = "2025", ratingBand = "未评分")).map { it.id })
        assertEquals(4, FilmFacetFilters.results(films, FilmFacetSelection()).size)
        assertFalse(FilmFacetSelection(ratingBand = "≥8.0").isEmpty)
        assertTrue(FilmFacetFilters.results(films,
            FilmFacetSelection(ratingBand = "≥9.0", year = "2025")).isEmpty())
        assertTrue(FilmFacetFilters.results(films,
            FilmFacetSelection(ratingBand = "不存在的档位")).isEmpty())
    }

    @Test fun `unknown region and director are never guessed from language or title`() {
        val missing = source("sparse", "2025")
        val known = source("complete", "2025").copy(
            directors = listOf("甲导演"), regions = listOf("韩国"))
        assertEquals(listOf("complete"), FilmFacetFilters.results(listOf(missing, known),
            FilmFacetSelection(region = "韩国", director = "甲导演")).map { it.id })
        assertTrue(FilmFacetFilters.results(listOf(missing),
            FilmFacetSelection(actor = "甲演员")).isEmpty())
    }

    @Test fun `same title separated by source year maps to correct official detail and keeps URLs`() {
        val movie2024 = source("2024-version", "2024")
        val movie2025 = source("2025-version", "2025")
        val urls = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            urls += url
            if (url.contains("/search/movie")) """{"results":[
              {"id":112,"title":"同名作品","release_date":"2024-01-01","poster_path":"/old.jpg"},
              {"id":225,"title":"同名作品","release_date":"2025-01-01","poster_path":"/new.jpg"}
            ]}""" else when {
                url.contains("/movie/112") -> """{"production_countries":[{"iso_3166_1":"JP"}],
                  "credits":{"cast":[{"name":"旧主演"}],"crew":[{"job":"Director","name":"旧导演"}]}}"""
                url.contains("/movie/225") -> """{"production_countries":[{"iso_3166_1":"CN"}],
                  "credits":{"cast":[{"name":"新主演"}],"crew":[{"job":"Director","name":"新导演"}]}}"""
                else -> null
            }
        })
        val a = FilmMetadataEnrichment.apply(movie2024, resolver.lookup(movie2024, token)!!)
        val b = FilmMetadataEnrichment.apply(movie2025, resolver.lookup(movie2025, token)!!)
        assertEquals("https://image.tmdb.org/t/p/w780/old.jpg", a.artworkUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/new.jpg", b.artworkUrl)
        assertEquals(listOf("旧主演"), a.actors)
        assertEquals(listOf("新主演"), b.actors)
        assertEquals(listOf("日本"), a.regions)
        assertEquals(listOf("中国大陆"), b.regions)
        assertTrue(urls.any { it.contains("/movie/112?append_to_response=credits") })
        assertTrue(urls.any { it.contains("/movie/225?append_to_response=credits") })
        assertEquals(movie2024.id, a.id)
        assertEquals(movie2024.cmsVodId, a.cmsVodId)
        assertEquals(movie2024.playbackEpisodes, a.playbackEpisodes)
        assertEquals(movie2025.id, b.id)
        assertEquals(movie2025.playbackEpisodes, b.playbackEpisodes)
        assertEquals(movie2024, FilmMetadataEnrichment.restore(a))
    }

    @Test fun `movie and tv credits use explicit countries and real people only`() {
        val movie = TmdbCreditsParser.parse("""{"original_language":"en",
           "production_countries":[{"iso_3166_1":"FR"},{"iso_3166_1":"JP"}],
           "credits":{"cast":[{"name":"演员一"},{"name":"演员二"}],
           "crew":[{"job":"Writer","name":"编剧"},{"job":"Director","name":"电影导演"}]}}
        """, "movie")
        assertEquals(listOf("法国", "日本"), movie.regions)
        assertEquals(listOf("演员一", "演员二"), movie.actors)
        assertEquals(listOf("电影导演"), movie.directors)
        val tv = TmdbCreditsParser.parse("""{"original_language":"en", "origin_country":["KR"],
           "created_by":[{"name":"原创主创"}], "credits":{"cast":[{"name":"剧集演员"}],
           "crew":[{"job":"Director","name":"剧集导演"}]}}""", "tv")
        assertEquals(listOf("韩国"), tv.regions)
        assertEquals(listOf("原创主创", "剧集导演"), tv.directors)
        assertEquals(listOf("剧集演员"), tv.actors)
        val unknown = TmdbCreditsParser.parse("""{"original_language":"ja"}""", "movie")
        assertTrue(unknown.regions.isEmpty())
        assertTrue(unknown.directors.isEmpty())
    }
}
