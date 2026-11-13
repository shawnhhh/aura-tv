package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** State/ownership regressions only; original values are not real-source evidence. */
class SelectedSpiderSearchStateTest {
    private fun film(id: String) = Film(id,id,"","电影","2026","",R.drawable.photofilm_orbit)
    @Test fun emptyTransportAndValidEmptyListRemainDifferent() = runBlocking {
        val state = TmdbGlobalSearchState().apply { requestKey = "current" }
        state.loadSelectedSpider("Original", "Original source") {
            SpiderResult.Failure("SOURCE_EMPTY_RESPONSE: original fixture")
        }
        assertEquals("SOURCE_EMPTY_RESPONSE", state.selectedSourceFailure)
        assertTrue(state.status.contains("尚不能判断"))
        state.loadSelectedSpider("Original", "Original source") { SpiderResult.Ready(emptyList()) }
        assertNull(state.selectedSourceFailure)
        assertTrue(state.status.contains("本次未返回匹配结果"))
        assertEquals(1, state.lastLoadedPage)
    }

    @Test fun cancelledOldSourceCannotPublishOrStopANewerSearch() = runBlocking {
        val state = TmdbGlobalSearchState().apply { requestKey = "old-source" }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = launch {
            state.loadSelectedSpider("Original", "Old source") {
                entered.complete(Unit)
                withContext(NonCancellable) {
                    release.await()
                    SpiderResult.Ready(listOf(film("old")))
                }
            }
        }
        entered.await()
        val fresh = film("new")
        state.requestKey = "new-source"
        state.films = listOf(fresh)
        state.loading = true
        old.cancel()
        release.complete(Unit)
        old.join()
        assertEquals(listOf(fresh), state.films)
        assertTrue("Old cleanup must not clear the new owner's loading state", state.loading)
    }

    @Test fun sourceDenialIsNotAnEmptyResourceListAndDoesNotExposeBody() = runBlocking {
        val state = TmdbGlobalSearchState().apply { requestKey = "current" }
        state.loadSelectedSpider("Original", "Original source") {
            SpiderResult.Failure("SOURCE_ACCESS_DENIED https://private.invalid/credential")
        }
        assertEquals("SOURCE_ACCESS_DENIED", state.selectedSourceFailure)
        assertFalse(state.status.contains("private.invalid"))
        assertFalse(state.loading)
        assertEquals(0, state.totalPages)
    }
}
