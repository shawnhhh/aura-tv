package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M471TmdbPlaybackRouteStoreTest {
    @Test fun returningToMovieRecoversItsOriginalCmsInsteadOfAnotherMovieSource() {
        val saved = HerrinTmdbPlaybackRouteStore()
        val first = TvBoxSite("Cinema A", needsAdapter = false,
            apiUrl = "https://first.example/api", key = "a")
        val second = TvBoxSite("Cinema B", needsAdapter = false,
            apiUrl = "https://second.example/api", key = "b")
        saved.cms("tmdb-movie-1", first)
        saved.personal("tmdb-movie-2")
        saved.cms("tmdb-movie-3", second)
        assertEquals(first, (saved.usable("tmdb-movie-1", null)
            as HerrinTmdbPlaybackRouteStore.Route.Cms).site)
        assertEquals(HerrinTmdbPlaybackRouteStore.Route.Personal,
            saved.usable("tmdb-movie-2", null))
        assertEquals(second, (saved.usable("tmdb-movie-3", null)
            as HerrinTmdbPlaybackRouteStore.Route.Cms).site)
    }

    @Test fun staleSpiderCannotBeReactivatedByReturningToItsTmdbPoster() {
        val saved = HerrinTmdbPlaybackRouteStore()
        saved.spider("tmdb-tv-5", "already-approved")
        assertNull(saved.usable("tmdb-tv-5", null))
        assertNull(saved.usable("tmdb-tv-5", "new-unapproved"))
        assertTrue(saved.usable("tmdb-tv-5", "already-approved")
            is HerrinTmdbPlaybackRouteStore.Route.Spider)
        saved.forget("tmdb-tv-5")
        assertNull(saved.usable("tmdb-tv-5", "already-approved"))
    }

    @Test fun applyingNewSourceConfigurationClearsEveryOldPlaybackRoute() {
        val saved = HerrinTmdbPlaybackRouteStore()
        saved.personal("tmdb-movie-1")
        saved.cms("tmdb-tv-2", TvBoxSite("Test", needsAdapter = false))
        saved.clear()
        assertNull(saved.forFilm("tmdb-movie-1"))
        assertNull(saved.forFilm("tmdb-tv-2"))
    }
}
