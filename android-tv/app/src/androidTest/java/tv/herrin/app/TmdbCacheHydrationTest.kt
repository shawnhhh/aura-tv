package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real cached JSON decoding, not a timing threshold or a mocked successful page. */
class TmdbCacheHydrationTest {
    @Test fun concurrentInitialHydrationAndRepeatedVisitsReadEachLoadedCacheOnlyOnce() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"hydrate-${UUID.randomUUID()}")
        try {
            val repository = TmdbBrowseRepository(TmdbBrowseFixture().apply { dense = true }, directory)
            TmdbBrowseState(repository,"fixture").refresh(TmdbBrowsePage.MOVIE)
            val restored = TmdbBrowseState(repository,"fixture")
            val specs = TmdbRailSpec.forPage(TmdbBrowsePage.MOVIE)
            coroutineScope { List(8) { async { restored.hydrate(TmdbBrowsePage.MOVIE) } }.awaitAll() }
            assertEquals(specs.size,repository.cachedPageReadAttempts.get())
            assertTrue(specs.all { restored.row(it).page == 1 && restored.row(it).entries.isNotEmpty() })
            val original = restored.row(specs.first())
            repeat(20) { restored.hydrate(TmdbBrowsePage.MOVIE) }
            assertEquals("Loaded revisits must not perform discarded disk restores",specs.size,repository.cachedPageReadAttempts.get())
            assertSame(original,restored.row(specs.first()))
        } finally { directory.deleteRecursively() }
    }
    @Test fun absentCachesStayRetryableWithoutErasingRowsAlreadyRefreshed() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"hydrate-${UUID.randomUUID()}")
        try {
            val repository = TmdbBrowseRepository(TmdbBrowseFixture().apply { dense = true }, directory)
            val restored = TmdbBrowseState(repository,"fixture")
            val specs = TmdbRailSpec.forPage(TmdbBrowsePage.MOVIE)
            restored.hydrate(TmdbBrowsePage.MOVIE)
            assertTrue(restored.rows.isEmpty())
            TmdbBrowseState(repository,"fixture").refresh(TmdbBrowsePage.MOVIE)
            restored.hydrate(TmdbBrowsePage.MOVIE)
            assertEquals(specs.size * 2,repository.cachedPageReadAttempts.get())
            assertTrue(specs.all { restored.row(it).page == 1 })
            restored.more(specs.first())
            val fresh = restored.row(specs.first())
            restored.hydrate(TmdbBrowsePage.MOVIE)
            assertSame("Hydration cannot roll back a real later page",fresh,restored.row(specs.first()))
        } finally { directory.deleteRecursively() }
    }
}
