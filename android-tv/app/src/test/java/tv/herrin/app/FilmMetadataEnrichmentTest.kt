package tv.herrin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilmMetadataEnrichmentTest {
    @get:Rule val temp = TemporaryFolder()

    private val token = "abcDEF1234567890abcDEF1234567890"
    private fun film() = Film(
        id = "spider-original-42", title = "时光之海【4K】", subtitle = "源内描述",
        category = "电影", year = "2025", synopsis = "无效的源内简介",
        artwork = R.drawable.photofilm_orbit,
        artworkUrl = "https://example.test/low.jpg",
        spiderVodId = "source-id-42", episodes = 1,
        playbackEpisodes = listOf(PlaybackEpisode(1, "第1集", listOf(
            PlaybackLine("spider-0", "线路一", "opaque-source-play-id")
        )))
    )

    @Test fun `tmdb metadata upgrades display but never rewrites playback identity`() {
        val original = film()
        val info = EnrichedArtwork("https://image.tmdb.org/t/p/w780/poster.jpg",
            "https://image.tmdb.org/t/p/original/wide.jpg", "官方简介", "2025",
            8.6, listOf("剧情", "科幻"), "电影")
        val updated = FilmMetadataEnrichment.apply(original, info)
        assertEquals(original.id, updated.id)
        assertEquals(original.spiderVodId, updated.spiderVodId)
        assertEquals(original.playbackEpisodes, updated.playbackEpisodes)
        assertEquals(original.title, updated.title)
        assertEquals("官方简介", updated.synopsis)
        assertEquals("TMDB 8.6/10", FilmMetadataEnrichment.ratingLabel(updated))
        assertEquals("TMDB", updated.metadataSource)
        assertEquals(listOf("剧情", "科幻"), updated.genres)
        assertEquals("https://image.tmdb.org/t/p/original/wide.jpg", updated.backdropUrl)
        val restored = FilmMetadataEnrichment.restore(updated)
        assertEquals(original, restored)
    }

    @Test fun `source fallback is preserved for missing metadata fields`() {
        val original = film()
        val updated = FilmMetadataEnrichment.apply(original, EnrichedArtwork(null, null))
        assertEquals(original.artworkUrl, updated.artworkUrl)
        assertEquals(original.synopsis, updated.synopsis)
        assertNull(FilmMetadataEnrichment.ratingLabel(updated))
    }

    @Test fun `classification handles documentary anime variety and genres`() {
        assertEquals("纪录片", TmdbGenreClassification.category("电影", "movie", listOf(99, 18)))
        assertEquals("动漫", TmdbGenreClassification.category("剧集", "tv", listOf(16)))
        assertEquals("综艺", TmdbGenreClassification.category("剧集", "tv", listOf(10764)))
        assertEquals("剧集", TmdbGenreClassification.category("电影", "tv", listOf(18)))
        assertEquals(listOf("纪录", "剧情"), TmdbGenreClassification.labels(listOf(99, 18)))
    }

    @Test fun `official search metadata supplies synopsis genres classification and clearly labeled score`() {
        val cache = temp.newFolder()
        var calls = 0
        val resolver = ArtworkMetadataResolver(cache, ArtworkJsonFetcher { _, _ ->
            calls++
            """{"results":[{"title":"时光之海","original_title":"时光之海",
                "release_date":"2025-01-01","poster_path":"/poster.jpg",
                "backdrop_path":"/wide.jpg","overview":"资料平台给出的简介",
                "vote_average":8.64,"vote_count":220,"genre_ids":[99,18]}]}"""
        })
        val metadata = resolver.lookup(film(), token)!!
        assertEquals("资料平台给出的简介", metadata.synopsis)
        assertEquals("纪录片", metadata.category)
        assertEquals(listOf("纪录", "剧情"), metadata.genres)
        assertEquals(8.64, metadata.rating!!, 0.00001)
        assertEquals(1, calls)
        val cached = ArtworkMetadataResolver(cache, ArtworkJsonFetcher { _, _ ->
            throw AssertionError("positive results must be stored")
        }).lookup(film(), token)!!
        assertEquals(metadata, cached)
    }

    @Test fun `sparse votes do not appear as an authoritative rating`() {
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { _, _ ->
            """{"results":[{"title":"时光之海","release_date":"2025-01-01",
              "poster_path":"/poster.jpg","vote_average":10.0,"vote_count":2}]}"""
        })
        assertNull(resolver.lookup(film(), token)?.rating)
    }
}
