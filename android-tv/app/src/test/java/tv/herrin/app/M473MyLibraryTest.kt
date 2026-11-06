package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M473MyLibraryTest {
    private fun film(id: String) = Film(
        id, "Film $id", "", "电影", "2025", "", R.drawable.photofilm_orbit)

    @Test fun recentFirstThenFavoritesDedupedAndStable() {
        val a = film("a")
        val b = film("b")
        val c = film("c")
        assertEquals(listOf(a, b, c),
            HerrinMyLibrary.combine(listOf(a, b), listOf(b, c)))
    }

    @Test fun neverManufacturesMissingSavedMovies() {
        assertTrue(HerrinMyLibrary.combine(emptyList(), emptyList()).isEmpty())
        val fromCatalog = listOf(film("authorized"))
        val selected = fromCatalog.filter { it.id in setOf("missing", "authorized") }
        assertEquals(fromCatalog, HerrinMyLibrary.combine(emptyList(), selected))
    }

    @Test fun sameTmdbTitleFromWatchAndFavoriteIsOneCard() {
        val tmdb = film("tmdb-movie-157336")
        assertEquals(listOf(tmdb), HerrinMyLibrary.combine(listOf(tmdb), listOf(tmdb)))
    }
}
