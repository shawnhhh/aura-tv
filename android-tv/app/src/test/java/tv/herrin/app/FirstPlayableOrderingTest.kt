package tv.herrin.app

import org.junit.Test
import org.junit.Assert.*

class FirstPlayableOrderingTest {
    private fun ep(number: Int, title: String, line: String) = PlaybackEpisode(number, title,
        listOf(PlaybackLine(line, "original", "https://example.invalid/" + number)))
    @Test fun reliableSeasonAndEpisodeCoordinatesOverrideProviderArrayOrder() {
        val film = Film("fixture", "Fixture", "", "剧集", "2026", "", 1,
            playbackEpisodes = listOf(ep(9, "S2E1", "season2"), ep(7, "S1E2", "season1"), ep(8, "S1E1", "season1")))
        assertEquals(8 to "season1", HerrinPlaybackEntry.firstPlayable(film))
    }
    @Test fun missingUrisNeverBecomePlayActions() {
        val film = Film("tmdb-movie-1", "Fixture", "", "电影", "2026", "", 1,
            playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(PlaybackLine("none", "none", "")))))
        assertNull(HerrinPlaybackEntry.firstPlayable(film))
        assertTrue(HerrinPlaybackEntry.unavailableTmdb(film))
    }
    @Test fun panelCanShowOneAuthoritativeSeasonWithoutChangingTheLegacyMultiSeasonIndex() {
        val film = Film("fixture", "Fixture", "", "剧集", "2026", "", 1,
            playbackEpisodes = listOf(ep(7, "S2E1", "season2")))
        assertNull(SeasonEpisodes.fromFilm(film))
        val panel = SeasonEpisodes.fromFilm(film, allowSingleSeason = true)!!
        assertEquals(2, panel.sections.single().number)
        assertEquals(7, panel.sections.single().episodes.single().source.number)
        assertEquals("season2", panel.sections.single().episodes.single().preferredLine(null).id)
    }
}
