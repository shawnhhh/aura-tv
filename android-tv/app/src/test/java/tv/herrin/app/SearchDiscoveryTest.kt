package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SearchDiscoveryTest {
    @Test fun queryIsTrimmedCaseInsensitiveAndCombinedWithCategory() {
        val films = DemoCatalog().titles()
        assertEquals(listOf("orbit"), SearchDiscovery.results(films, "  LAST orbit  ", "全部", false).map { it.id })
        assertEquals(listOf("tide"), SearchDiscovery.results(films, "潮汐", "剧集", false).map { it.id })
        assertTrue(SearchDiscovery.results(films, "潮汐", "电影", false).isEmpty())
        assertEquals(2, SearchDiscovery.results(films, "", "动漫", false).size)
    }

    @Test fun newestSortIsStableAndDoesNotMutateSourceOrder() {
        val films = DemoCatalog().titles()
        val sorted = SearchDiscovery.results(films, "", "全部", true)
        assertEquals(2026, sorted.first().year.toInt())
        assertEquals(2025, sorted.last().year.toInt())
        assertEquals(listOf("orbit", "tide", "dawn", "night", "return", "blue"), films.map { it.id })
        assertEquals(films, SearchDiscovery.results(films, "", "全部", false))
    }
}
