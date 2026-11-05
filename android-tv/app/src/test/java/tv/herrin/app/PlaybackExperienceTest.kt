package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
class PlaybackExperienceTest {
    private fun film(vararg names: String) = Film("series", "Original", "", "剧集", "", "", 0,
        playbackEpisodes = names.mapIndexed { index, name -> PlaybackEpisode(index+21, name,
            listOf(PlaybackLine("main", "main", "https://original.invalid/$index.mp4"))) })
    @Test fun nextEpisodeUsesSourceIndexAndNeverBridgesMissingEpisodesOrSeasons() {
        val source = film("S01E01", "S01E02", "S01E04", "S02E01")
        assertEquals(PlaybackRequest("series",22,"main"), SeasonPlayback.adjacent(source,21,"main",1))
        assertNull(SeasonPlayback.adjacent(source,22,"main",1))
        assertNull(SeasonPlayback.adjacent(source,24,"main",-1))
    }
    @Test fun singleSeasonAndUnknownLabelsAreHandledWithoutGuessing() {
        assertEquals(22, SeasonPlayback.adjacent(film("S01E01","S01E02"),21,"main",1)?.episode)
        assertEquals(21, SeasonPlayback.adjacent(film("第1集","第2集"),22,"main",-1)?.episode)
        assertNull(SeasonPlayback.adjacent(film("正片","花絮"),21,"main",1))
    }
    @Test fun explicitHttpDenialsNeverBecomeAutomaticSourceFallback() {
        for (code in listOf("PROXY_HTTP_403","HTTP_401","HTTP_429","PROXY_INVALID_SESSION")) {
            assertTrue(HerrinAutomaticPlayback.resolutionFailure(code).kind in listOf(
                FongMiPlaybackRecovery.Kind.NETWORK_POLICY,FongMiPlaybackRecovery.Kind.AUTHENTICATION))
        }
    }
}
