package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Reproducible scheduler comparison; these numbers are fixture latency, never TCL or real-provider speed. */
class DetailLayoutControlledPerformanceTest {
    @Test fun compareWaitForAllBaselineWithIncrementalLayoutPublication() = runBlocking {
        val baseline = mutableListOf<Long>()
        val candidate = mutableListOf<Long>()
        repeat(5) {
            val movie = HerrinSourceMovie("fixture", HerrinSourceType.TVBOX_CMS,
                Film("fixture", "Fixture", "", "电影", "2026", "", R.drawable.photofilm_orbit, cmsVodId = "one"), "movie")
            val detailed = HerrinSourceDiscovery.Detail(movie.film.copy(playbackEpisodes = listOf(
                PlaybackEpisode(1, "正片", listOf(PlaybackLine("one", "abc", "https://example.invalid/media"))))))
            // Baseline UI required a user selection after discovery: zero simulated
            // human delay here, making this a conservative scheduling-only comparison.
            val before = System.nanoTime()
            coroutineScope { launch { delay(20) }; launch { delay(200) } }
            delay(20)
            baseline += (System.nanoTime() - before) / 1_000_000
            val start = System.nanoTime()
            var first: Long? = null
            val fast = HerrinSourceDiscovery.Node("fast", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(20); listOf(movie) }, { delay(20); detailed })
            val slow = HerrinSourceDiscovery.Node("slow", "Fixture", HerrinSourceType.TVBOX_CMS,
                { delay(200); emptyList() }, { null })
            HerrinSourceDiscovery().search("r", listOf(fast, slow), null, Mutex()) {
                if (first == null && it.routes.isNotEmpty()) first = (System.nanoTime() - start) / 1_000_000
            }
            assertNotNull(first)
            candidate += first!!
        }
        println("DETAIL_LAYOUT_CONTROLLED baseline_ms=$baseline candidate_ms=$candidate human_delay_ms=0 real_device=0")
        assertTrue("Streaming must not wait for the intentionally slow search lane", candidate.sorted()[2] < baseline.sorted()[2])
    }
}
