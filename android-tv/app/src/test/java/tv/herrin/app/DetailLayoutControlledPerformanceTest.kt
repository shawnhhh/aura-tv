package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Reproducible scheduler comparison; these numbers are fixture latency, never TCL or real-provider speed. */
class DetailLayoutControlledPerformanceTest {
    @Test fun compareFrozenBaselineWithIncrementalLayoutPublication() = runBlocking {
        val baseline = mutableListOf<Long>()
        val candidate = mutableListOf<Long>()
        repeat(5) {
            val movie = HerrinSourceMovie("fixture", HerrinSourceType.TVBOX_CMS,
                Film("fixture", "Fixture", "", "电影", "2026", "", R.drawable.photofilm_orbit, cmsVodId = "one"), "movie")
            val detailed = HerrinSourceDiscovery.Detail(movie.film.copy(playbackEpisodes = listOf(
                PlaybackEpisode(1, "正片", listOf(PlaybackLine("one", "abc", "https://example.invalid/media"))))))
            // Run the actual frozen baseline. Its preferred search completes
            // before fallback dispatch. UI selection is immediate (zero human delay).
            val oldReady = CompletableDeferred<BaselineSourceDiscoveryF8.Hit>()
            val oldLock = Mutex()
            val oldFast = BaselineSourceDiscoveryF8.Node("fast", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(20); listOf(movie) }, { delay(20); BaselineSourceDiscoveryF8.Detail(detailed.film) })
            val oldSlow = BaselineSourceDiscoveryF8.Node("slow", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(200); emptyList() }, { null })
            val before = System.nanoTime()
            val oldJob = launch { BaselineSourceDiscoveryF8().search("r", listOf(oldFast, oldSlow), "slow", oldLock) {
                it.hits.firstOrNull()?.let { hit -> oldReady.complete(hit) }
            } }
            val oldHit = withTimeout(2000) { oldReady.await() }
            oldJob.cancelAndJoin()
            val oldDetail = oldLock.withLock { oldHit.node.details(oldHit.movie) }
            assertTrue(oldDetail?.film?.playbackEpisodes?.isNotEmpty() == true)
            baseline += (System.nanoTime() - before) / 1_000_000
            val start = System.nanoTime()
            var first: Long? = null
            val fast = HerrinSourceDiscovery.Node("fast", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(20); listOf(movie) }, { delay(20); detailed })
            val slow = HerrinSourceDiscovery.Node("slow", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(200); emptyList() }, { null })
            HerrinSourceDiscovery().search("r", listOf(fast, slow), "slow", Mutex()) {
                if (first == null && it.routes.isNotEmpty()) first = (System.nanoTime() - start) / 1_000_000
            }
            assertNotNull(first)
            candidate += first!!
        }
        println("DETAIL_LAYOUT_CONTROLLED baseline_ms=$baseline candidate_ms=$candidate human_delay_ms=0 real_device=0")
        assertTrue("Streaming must not wait for the intentionally slow search lane", candidate.sorted()[2] < baseline.sorted()[2])
    }
}
