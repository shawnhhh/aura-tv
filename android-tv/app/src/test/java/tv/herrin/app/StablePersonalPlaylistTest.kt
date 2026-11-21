package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class StablePersonalPlaylistTest {
    private fun offer(id: String, season: Int? = 2, episode: Int? = 3) = HerrinSourceMovie("personal:connection",
        HerrinSourceType.NAS, Film(id, "Fixture", "file-$id", "剧集", "2026", "", 1),
        "tv", season = season, episode = episode)
    private fun build(items: List<HerrinSourceMovie>, signature: String = "old") =
        HerrinPersonalPlaylistBuilder.createStable(items) {
            PlaybackResult.Ready(PlaybackMedia("https://example.invalid/${it.film.id}?signature=$signature"))
        }
    @Test fun stableFileRouteSurvivesReorderingAndTemporaryUrlChanges() {
        val a = offer("a"); val b = offer("b")
        val first = build(listOf(a,b))
        val second = build(listOf(b,a), "new")
        val route = first.mediaByLine.entries.single { it.value.uri.contains("/a?") }.key
        assertTrue(second.mediaByLine.getValue(route).uri.contains("/a?"))
        assertEquals(first.mediaByLine.keys, second.mediaByLine.keys)
        assertFalse(route.contains("signature"))
        assertEquals("S2E3", second.episodes.single().title)
    }
    @Test fun unmarkedTvFilesNeverBecomeFabricatedSeasonOneEpisodeOneOrOneMergedEpisode() {
        val playlist = build(listOf(offer("a", null, null), offer("b", null, null)))
        assertEquals(2, playlist.episodes.size)
        assertEquals(setOf("file-a", "file-b"), playlist.episodes.map { it.title }.toSet())
        assertTrue(playlist.episodes.all { it.lines.size == 1 })
        assertNull(SeasonEpisodes.fromFilm(Film("fixture", "Fixture", "", "剧集", "2026", "", 1,
            playbackEpisodes = playlist.episodes), allowSingleSeason = true))
    }
    @Test fun sameLineLabelsAcrossDifferentFilesDoNotDeduplicateRealVersions() {
        val playlist = build(listOf(offer("a"), offer("b")))
        assertEquals(2, playlist.episodes.single().lines.size)
        assertEquals(1, playlist.episodes.single().lines.map { it.title }.distinct().size)
        assertEquals(2, playlist.episodes.single().lines.map { it.id }.distinct().size)
    }
}
