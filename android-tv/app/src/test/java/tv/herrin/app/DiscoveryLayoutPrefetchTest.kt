package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class DiscoveryLayoutPrefetchTest {
    private fun offer(id: String) = HerrinSourceMovie("cms", HerrinSourceType.TVBOX_CMS,
        Film("film-$id", "Film", "", "电影", "2026", "", R.drawable.photofilm_orbit, cmsVodId = id), "movie")
    private fun detail(movie: HerrinSourceMovie, episodes: Int) = HerrinSourceDiscovery.Detail(movie.film.copy(
        playbackEpisodes = (1..episodes).map { number -> PlaybackEpisode(number, "第${number}集",
            listOf(PlaybackLine("one", "abc", "https://private.invalid/signed?token=private"),
                PlaybackLine("two", "abc", "https://private.invalid/other"))) }))

    @Test fun firstLayoutIsPublishedBeforeSlowProviderCompletes() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<HerrinSourceDiscovery.Snapshot>()
        val fast = HerrinSourceDiscovery.Node("fast", "CMS", HerrinSourceType.TVBOX_CMS,
            { listOf(offer("a")) }, { detail(it, 2) })
        val slow = HerrinSourceDiscovery.Node("slow", "CMS2", HerrinSourceType.TVBOX_CMS,
            { release.await(); emptyList() }, { null })
        val job = launch { HerrinSourceDiscovery().search("r", listOf(fast, slow), null, Mutex()) {
            snapshots += it
            if (it.routes.isNotEmpty()) ready.complete(Unit)
        } }
        withTimeout(1000) { ready.await() }
        assertFalse(job.isCompleted)
        assertEquals(2, snapshots.last().routes.size)
        assertFalse(snapshots.last().hits.single().episodes.toString().contains("private"))
        release.complete(Unit)
        job.join()
        assertEquals(2, snapshots.last().checked)
    }

    @Test fun sameNamedLinesKeepVodAndEpisodeOwnershipAndAppendStableNumbers() = runBlocking {
        val service = HerrinSourceDiscovery()
        var latest = HerrinSourceDiscovery.Snapshot("", 0)
        val node = HerrinSourceDiscovery.Node("node", "CMS", HerrinSourceType.TVBOX_CMS,
            { listOf(offer("a"), offer("b")) }, { detail(it, if (it.film.cmsVodId == "a") 2 else 1) })
        service.search("r", listOf(node), null, Mutex()) { latest = it }
        assertEquals(4, latest.routes.size)
        assertEquals(listOf("node:a", "node:a", "node:b", "node:b"), latest.routes.map { it.hitKey })
        assertEquals(listOf(2, 1), latest.hits.map { it.episodes.size })
        val original = latest.routes
        service.search("r", listOf(node), null, Mutex()) { latest = it }
        assertEquals(original, latest.routes)
        assertEquals(2, latest.hits.size)
    }
}
