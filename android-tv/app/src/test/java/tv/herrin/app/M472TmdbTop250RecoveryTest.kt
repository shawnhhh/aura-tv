package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class M472TmdbTop250RecoveryTest {
    private fun movie(id: Int): String =
        """{"id":$id,"title":"Film $id","release_date":"2001-02-03",
           "poster_path":"/poster.jpg","vote_count":100,"vote_average":8.3}"""

    @Test fun emptyNetworkResponseDoesNotStayCachedForSixHours() = runBlocking {
        val count = AtomicInteger()
        val parser = HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ ->
            if (count.incrementAndGet() == 1) null
            else """{"results":[${movie(42)}]}"""
        }, clock = { 100L })
        val top = parser.sections.first { it.key == "top250" }
        assertTrue(parser.load(top, "test-token").films.isEmpty())
        assertEquals("tmdb-movie-42", parser.load(top, "test-token").films.single().id)
        assertEquals(2, count.get())
        assertEquals(1, parser.load(top, "test-token").films.size)
        assertEquals(2, count.get()) // successful page is cached
    }

    @Test fun failedTop250PageCanResumeAtNextTryWithoutLosingGoodPages() = runBlocking {
        val page2 = AtomicInteger()
        val parser = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            val page = Regex("""page=(\d+)""").find(url)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (page == 2 && page2.incrementAndGet() == 1) null
            else """{"results":[${(1..20).joinToString(",") { movie((page-1)*20+it) }}]}"""
        })
        val first = parser.loadTop250("valid-token")
        assertEquals(20, first.films.size)
        assertFalse(first.isComplete)
        val next = parser.loadTop250("valid-token")
        assertEquals(250, next.films.size)
        assertTrue(next.isComplete)
        assertEquals(2, page2.get())
        assertEquals(250, next.films.map { it.id }.distinct().size)
    }

    @Test fun top250RendersFirstPageBeforeRemainingNetworkRequestsComplete() = runBlocking {
        val counts = mutableListOf<Int>()
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            val page = Regex("""page=(\d+)""").find(url)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (page > 2) null
            else """{"results":[${(1..20).joinToString(",") { movie((page-1)*20+it) }}]}"""
        })
        val output = discovery.loadTop250("token", onProgress = { counts += it.films.size })
        assertEquals(listOf(20, 40), counts)
        assertEquals(40, output.films.size)
        assertFalse(output.isComplete)
    }

    @Test fun tmdbTop250IsNotAnImdbLicensedChart() {
        val discovery = HerrinTmdbDiscovery()
        assertTrue(discovery.sections.first { it.key == "top250" }.title.contains("TMDB"))
    }
}
