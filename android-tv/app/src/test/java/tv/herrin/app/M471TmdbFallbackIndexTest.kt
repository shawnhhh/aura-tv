package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M471TmdbFallbackIndexTest {
    @Test fun onlyTheFailedProviderIsSkippedAndOtherFilmsStayIndependent() {
        val index = HerrinTmdbFallbackIndex()
        index.select("tmdb-movie-10", "webdav:NAS")
        index.select("tmdb-movie-20", "cms:other")
        assertTrue(index.failSelected("tmdb-movie-10"))
        assertEquals(setOf("webdav:NAS"), index.rejected("tmdb-movie-10"))
        assertEquals("cms:other", index.selected("tmdb-movie-20"))
        assertTrue(index.rejected("tmdb-movie-20").isEmpty())
        index.select("tmdb-movie-10", "webdav:CLOUD")
        assertTrue(index.failSelected("tmdb-movie-10"))
        assertEquals(setOf("webdav:NAS", "webdav:CLOUD"),
            index.rejected("tmdb-movie-10"))
        assertFalse(index.failSelected("tmdb-movie-10"))
    }

    @Test fun explicitRetryRestoresAllSourcesAndSettingsResetClearsEverything() {
        val index = HerrinTmdbFallbackIndex()
        index.select("tmdb-tv-5", "cms:first")
        index.failSelected("tmdb-tv-5")
        index.reset("tmdb-tv-5")
        assertTrue(index.rejected("tmdb-tv-5").isEmpty())
        assertNull(index.selected("tmdb-tv-5"))
        index.select("tmdb-tv-5", "spider:approved")
        index.clear()
        assertNull(index.selected("tmdb-tv-5"))
        assertTrue(index.rejected("tmdb-tv-5").isEmpty())
    }

    @Test fun sourceIdentifiersCannotBeEmptyAndMustBelongToTmdbFilms() {
        val index = HerrinTmdbFallbackIndex()
        assertThrows(IllegalArgumentException::class.java) { index.select("local-1", "nas") }
        assertThrows(IllegalArgumentException::class.java) { index.select("tmdb-tv-1", "") }
    }
}
