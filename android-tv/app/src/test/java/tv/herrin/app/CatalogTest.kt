package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val source = DemoCatalog()
    @Test fun searchMatchesChineseAndTrimmedEnglish() {
        assertEquals("orbit", source.search("轨道").single().id)
        assertEquals("orbit", source.search("  last ORBIT  ").single().id)
        assertTrue(source.search("not-a-film").isEmpty())
        assertEquals(source.titles(), source.search(""))
    }
    @Test fun fixturesHaveStableUniqueIdsAndEpisodes() {
        val films = source.titles()
        assertEquals(films.size, films.map { it.id }.toSet().size)
        assertTrue(films.all { it.episodes >= 1 && it.artwork != 0 })
        assertEquals(setOf("电影", "剧集", "动漫"), films.map { it.category }.toSet())
    }
}
