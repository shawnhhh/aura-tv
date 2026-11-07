package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Fixtures verify protocol and identity only; they are not evidence of live TMDB availability. */
class P0GlobalTmdbSearchTest {
    @Test fun globalEndpointEncodesChineseAndExcludesPeople() = runBlocking {
        var requested = ""
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            requested = url
            """{"page":1,"total_pages":2,"results":[
                {"id":77,"media_type":"movie","title":"小城良方"},
                {"id":77,"media_type":"tv","name":"A series"},
                {"id":77,"media_type":"person","name":"A person"},
                {"id":77,"media_type":"movie","title":"duplicate"}]}"""
        })
        val result = discovery.search("小城良方", "fixture-token")
        assertTrue(requested.contains("/search/multi?"))
        assertTrue(requested.contains("query=%E5%B0%8F%E5%9F%8E%E8%89%AF%E6%96%B9"))
        assertTrue(requested.contains("language=zh-CN"))
        assertEquals(listOf("tmdb-movie-77", "tmdb-tv-77"), result.films.map { it.id })
        assertEquals(2, result.totalPages)
    }
    @Test fun blankDoesNotRequestOrLoadDemo() = runBlocking {
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> error("must not request") })
        assertTrue(discovery.search("  ", "").films.isEmpty())
    }
    @Test fun pagingAndEmptyLiveResponseRemainEmpty() = runBlocking {
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            assertTrue(url.contains("page=2"))
            """{"page":2,"total_pages":2,"results":[]}"""
        })
        assertTrue(discovery.search("never preloaded", "fixture-token", 2).films.isEmpty())
    }
    @Test fun failedRequestDoesNotClaimNoResults() = runBlocking {
        val state = TmdbGlobalSearchState()
        state.load(HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> null }), "query", "fixture-token")
        assertTrue(state.status.contains("失败"))
        assertTrue(state.films.isEmpty())
    }
    @Test fun authAndRateLimitHaveExplicitRecoveryStatus() = runBlocking {
        for ((failure, expected) in listOf(ArtworkAuthenticationFailure(401) to "授权", ArtworkRateLimitFailure() to "限流")) {
            val state = TmdbGlobalSearchState()
            state.load(HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> throw failure }), "query", "fixture-token")
            assertTrue(state.status.contains(expected))
        }
    }
    @Test fun cancelledOldQueryCannotOverwriteNewResults() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            if (url.contains("query=old")) {
                started.complete(Unit)
                Thread.sleep(10_000)
                error("old call must be interrupted")
            }
            """{"page":1,"total_pages":1,"results":[{"media_type":"movie","id":2,"title":"new"}]}"""
        })
        val state = TmdbGlobalSearchState()
        val old = launch { state.load(discovery, "old", "fixture-token") }
        withTimeout(1000) { started.await() }
        old.cancelAndJoin()
        state.load(discovery, "new", "fixture-token")
        assertEquals(listOf("tmdb-movie-2"), state.films.map { it.id })
        assertFalse(state.loading)
    }
    @Test fun failedNextPageCanRetryWithoutSkippingOrDuplicatingFilms() = runBlocking {
        var secondAttempts = 0
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
            if (url.contains("page=2")) {
                if (++secondAttempts == 1) throw java.io.IOException("fixture offline")
                """{"page":2,"total_pages":3,"results":[{"media_type":"movie","id":1,"title":"one"},{"media_type":"tv","id":1,"name":"series"}]}"""
            } else """{"page":1,"total_pages":3,"results":[{"media_type":"movie","id":1,"title":"one"}]}"""
        })
        val state = TmdbGlobalSearchState()
        state.load(discovery, "query", "fixture-token")
        state.page = 2
        state.load(discovery, "query", "fixture-token")
        assertEquals(1, state.lastLoadedPage)
        assertEquals(1, state.films.size)
        assertTrue(state.status.contains("失败"))
        state.load(discovery, "query", "fixture-token")
        assertEquals(2, state.lastLoadedPage)
        assertEquals(listOf("tmdb-movie-1", "tmdb-tv-1"), state.films.map { it.id })
    }

}
