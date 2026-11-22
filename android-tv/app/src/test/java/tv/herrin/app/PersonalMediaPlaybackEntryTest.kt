package tv.herrin.app

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class PersonalMediaPlaybackEntryTest {
    private fun indexed() = Film("personal-index:representative", "Personal", "", "剧集", "2026", "", 1,
        playbackEpisodes = listOf(
            PlaybackEpisode(8, "第8集", listOf(PlaybackLine("personal-file:episode-eight", "原文件", "personal-index:episode-eight"))),
            PlaybackEpisode(2, "第2集", listOf(PlaybackLine("personal-file:episode-two", "原文件", "personal-index:episode-two")))))

    @Test fun groupedPersonalCardKeepsTheOriginalFileIdentityAndEpisode() {
        val original = indexed()
        val selected = requireNotNull(PersonalMediaPlaybackEntry.from(original))
        assertEquals("personal-representative", selected.film.id)
        assertEquals(2, selected.episode)
        assertEquals("personal-file:episode-two", selected.line)
        assertEquals(original.playbackEpisodes, selected.film.playbackEpisodes)
    }

    @Test fun noForeignRouteOrInventedEpisodeCanBeOpenedAsAPrivateFile() {
        assertNull(PersonalMediaPlaybackEntry.from(indexed().copy(id = "tmdb-movie-1")))
        assertNull(PersonalMediaPlaybackEntry.from(indexed().copy(playbackEpisodes = emptyList())))
        val wrong = indexed().copy(playbackEpisodes = listOf(PlaybackEpisode(1, "正片",
            listOf(PlaybackLine("personal-file:file", "伪装线路", "https://example.invalid/foreign")))))
        assertNull(PersonalMediaPlaybackEntry.from(wrong))
    }

    @Test fun privateSelectionRetiresPublicWorkWithoutDeletingOtherFilmsOrKeepingAnOldMediaUrl() {
        val state = TmdbScreenState()
        val pending = Job()
        state.sourceSearchJob = pending
        state.discoverySelection = "tmdb-movie-5" to "node"
        state.tmdbPlaybackRoutes.cms("other-film", TvBoxSite("Other", key = "other", needsAdapter = false))
        val selected = requireNotNull(PersonalMediaPlaybackEntry.from(indexed()))
        state.tmdbPersonalPlayback[selected.film.id] = mapOf(selected.line to PlaybackMedia("https://example.invalid/expired"))
        val version = state.tmdbSearchGeneration.get()
        state.selectPersonal(selected)
        assertTrue(pending.isCancelled)
        assertTrue(state.tmdbSearchGeneration.get() > version)
        assertNull(state.discoverySelection)
        assertNull(state.tmdbPersonalPlayback[selected.film.id])
        assertTrue(state.tmdbPlaybackRoutes.forFilm(selected.film.id) is HerrinTmdbPlaybackRouteStore.Route.Personal)
        assertNotNull(state.tmdbPlaybackRoutes.forFilm("other-film"))
        assertEquals(selected.film, state.tmdbResolved[selected.film.id])
    }
}
