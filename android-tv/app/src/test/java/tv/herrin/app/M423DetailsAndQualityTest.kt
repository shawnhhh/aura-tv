package tv.herrin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M423DetailsAndQualityTest {
    @get:Rule val temp = TemporaryFolder()
    private val token = "abcDEF1234567890abcDEF1234567890"

    @Test fun `official details supply credits portraits dates and runtime without touching episodes`() {
        val film = Film("source-123", "测试电影", "源数据", "电影", "2024", "源简介",
            R.drawable.photofilm_orbit,
            playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(
                PlaybackLine("origin", "4K", "https://licensed.example.test/movie.m3u8")))))
        val endpoints = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            endpoints += url
            if (url.contains("/search/movie")) """{"results":[{
              "id":77,"title":"测试电影","release_date":"2024-07-21",
              "poster_path":"/poster.jpg","vote_average":8.6,"vote_count":500}]}"""
            else """{"release_date":"2024-07-21","runtime":178,
              "production_countries":[{"iso_3166_1":"US"}],
              "credits":{"cast":[{"name":"演员甲","character":"角色乙","profile_path":"/actor.jpg"},
                                  {"name":"无照片演员","character":"客串"}],
                         "crew":[{"job":"Director","name":"导演丙","profile_path":"/director.jpg"}]}}"""
        })
        val metadata = resolver.lookup(film, token)!!
        assertEquals("2024-07-21", metadata.releaseDate)
        assertEquals(178, metadata.runtimeMinutes)
        assertEquals(listOf("导演丙", "演员甲", "无照片演员"), metadata.people.map { it.name })
        assertEquals("饰 角色乙", metadata.people[1].role)
        assertEquals("https://image.tmdb.org/t/p/w185/actor.jpg", metadata.people[1].portraitUrl)
        assertNull(metadata.people[2].portraitUrl)
        val enriched = FilmMetadataEnrichment.apply(film, metadata)
        assertEquals(film.id, enriched.id)
        assertEquals(film.playbackEpisodes, enriched.playbackEpisodes)
        assertEquals("TMDB 8.6/10", FilmMetadataEnrichment.ratingLabel(enriched))
        assertEquals(film, FilmMetadataEnrichment.restore(enriched))
        assertTrue(endpoints.any { it.contains("/movie/77?append_to_response=credits") })
    }

    @Test fun `portrait url sanitizer rejects tracking domains and traversal paths`() {
        assertNull(TmdbPortraits.url("//evil.example/track.jpg"))
        assertNull(TmdbPortraits.url("/../private.jpg"))
        assertNull(TmdbPortraits.url("https://evil.example/a.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w185/normal.png", TmdbPortraits.url("/normal.png"))
    }

    @Test fun `selected video and audio formats generate conservative verified quality`() {
        assertEquals(listOf("4K", "Dolby Vision", "Dolby Atmos"), MediaQuality.verified(
            SelectedTrackEvidence("video/dolby-vision", 3840, 2160, 6),
            SelectedTrackEvidence("audio/eac3-joc")).labels())
        assertEquals(listOf("1080p", "HDR (PQ)"), MediaQuality.verified(
            SelectedTrackEvidence("video/hevc", 1920, 1080, 6),
            SelectedTrackEvidence("audio/eac3")).labels())
        assertEquals(listOf("720p", "HDR (HLG)"), MediaQuality.verified(
            SelectedTrackEvidence("video/hevc", 1280, 720, 7), null).labels())
        assertFalse(MediaQuality.verified(null, null).isKnown)
        assertFalse(MediaQuality.verified(null, SelectedTrackEvidence("audio/eac3")).isKnown)
    }

    @Test fun `claimed media specs stay separate from selected track evidence`() {
        assertEquals(listOf("4K", "Dolby Vision", "HDR", "Dolby Atmos"),
            SourceMediaClaims.labels("测试电影[4K HDR]", "Dolby Vision · Atmos"))
        assertEquals(listOf("1080p"), SourceMediaClaims.labels("测试电影", "1080p线路"))
        assertTrue(SourceMediaClaims.labels("测试电影", "默认线路").isEmpty())
        assertTrue(SourceMediaClaims.labels("My4KTest", "HDRip").isEmpty())
        assertTrue(SourceMediaClaims.labels("测试电影", "普通杜比").isEmpty())
    }
}
