package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M468PersonalPlaylistTest {
    private fun offer(source: String, season: Int?, ep: Int?, quality: Int): HerrinSourceMovie {
        val film = Film("source-" + source + season + ep + quality,
            "演示剧集", "S" + season + "E" + ep, "剧集", "2025",
            "", R.drawable.photofilm_orbit)
        return HerrinSourceMovie(source, HerrinSourceType.NAS, film,
            "tv", season = season, episode = ep, resolutionClaim = quality)
    }

    @Test fun eachTvEpisodeUsesItsOwnURIAndKeepsQualityAlternatives() {
        val raw = listOf(offer("nas", 1, 1, 2160),
            offer("nas", 1, 1, 1080), offer("nas", 1, 2, 1080),
            offer("nas", 2, 1, 2160))
        val mapped = HerrinPersonalPlaylistBuilder.create(raw) { offered ->
            PlaybackResult.Ready(PlaybackMedia(
                "https://nas.example.org/" + offered.season + "/" +
                    offered.episode + "/" + offered.resolutionClaim + ".mkv"))
        }
        assertEquals(3, mapped.episodes.size)
        assertEquals(listOf("S1E1", "S1E2", "S2E1"),
            mapped.episodes.map { it.title })
        assertEquals(2, mapped.episodes[0].lines.size)
        val first = mapped.episodes.first().lines.first()
        val alternate = mapped.episodes.first().lines[1]
        assertEquals("https://nas.example.org/1/1/2160.mkv",
            mapped.mediaByLine[first.id]!!.uri)
        assertEquals("https://nas.example.org/1/1/1080.mkv",
            mapped.mediaByLine[alternate.id]!!.uri)
        assertNotEquals(mapped.mediaByLine[mapped.episodes[2].lines.first().id]!!.uri,
            mapped.mediaByLine[first.id]!!.uri)
        assertNotNull(SeasonEpisodes.fromFilm(Film(
            "series", "Series", "", "剧集", "2025", "", R.drawable.photofilm_orbit,
            playbackEpisodes = mapped.episodes)))
    }

    @Test fun filmQualityVariantsStayInOneTitleCard() {
        val items = listOf(offer("nas", null, null, 2160).copy(mediaType = "movie"),
            offer("nas", null, null, 1080).copy(mediaType = "movie"))
        val result = HerrinPersonalPlaylistBuilder.create(items) {
            PlaybackResult.Ready(PlaybackMedia("https://nas.test/movie_" + it.resolutionClaim + ".mkv"))
        }
        assertEquals(1, result.episodes.size)
        assertEquals("正片", result.episodes[0].title)
        assertEquals(2, result.episodes[0].lines.size)
    }
}
