package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class DiscoverySelectionFailureTest {
    private fun movie(id: String) = Film(id, "Fixture", "", "电影", "2026", "", 1,
        cmsVodId = "vod-$id", playbackEpisodes = listOf(PlaybackEpisode(1, "正片",
            listOf(PlaybackLine("line", "线路", "https://example.invalid/$id")))))

    @Test fun failedRebindCannotLeaveOldEpisodesOrPlaybackProvenanceAndKeepsOtherFilms() {
        val state = TmdbScreenState()
        val selected = movie("tmdb-movie-1")
        val other = movie("tmdb-movie-2")
        for (film in listOf(selected, other)) {
            state.tmdbResolved[film.id] = film
            state.tmdbPlaybackRoutes.personal(film.id)
            state.tmdbPersonalPlayback[film.id] = mapOf("line" to PlaybackMedia("https://example.invalid/media"))
        }
        state.loadedDiscoveryHits[selected.id] = "node-a:vod-a"
        state.requestedDiscoveryHits[selected.id] = "node-b:vod-b"
        state.invalidateDiscoverySelection(selected.id, "node-b:vod-b")

        val unavailable = state.tmdbResolved.getValue(selected.id)
        assertTrue(HerrinPlaybackEntry.unavailableTmdb(unavailable))
        assertTrue(unavailable.playbackEpisodes.isEmpty())
        assertNull(unavailable.cmsVodId)
        assertNull(state.tmdbPlaybackRoutes.forFilm(selected.id))
        assertNull(state.tmdbPersonalPlayback[selected.id])
        assertFalse(state.sourceSelectionReady(selected.id))
        assertSame(other, state.tmdbResolved[other.id])
        assertNotNull(state.tmdbPlaybackRoutes.forFilm(other.id))
        assertNotNull(state.tmdbPersonalPlayback[other.id])
    }

    @Test fun anObsoleteFailureCannotRevokeTheNewlySelectedFilmProjection() {
        val state = TmdbScreenState()
        val current = movie("tmdb-movie-1")
        state.tmdbResolved[current.id] = current
        state.tmdbPlaybackRoutes.personal(current.id)
        state.loadedDiscoveryHits[current.id] = "node-b:vod-b"
        state.requestedDiscoveryHits[current.id] = "node-b:vod-b"
        state.invalidateDiscoverySelection(current.id, "node-a:vod-a")
        assertSame(current, state.tmdbResolved[current.id])
        assertFalse(HerrinPlaybackEntry.unavailableTmdb(current))
        assertTrue(state.sourceSelectionReady(current.id))
        assertNotNull(state.tmdbPlaybackRoutes.forFilm(current.id))
    }
}
