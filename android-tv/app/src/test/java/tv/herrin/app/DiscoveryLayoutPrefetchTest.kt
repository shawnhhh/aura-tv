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

    @Test fun canceledPrefetchResumesWithoutRepeatingTerminalSearch() = runBlocking {
        var searches = 0
        var loads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val node = HerrinSourceDiscovery.Node("node", "CMS", HerrinSourceType.TVBOX_CMS,
            { searches++; listOf(offer("a")) }, {
                loads++; entered.complete(Unit); release.await(); detail(it, 1)
            })
        val service = HerrinSourceDiscovery()
        val job = launch { service.search("r", listOf(node), null, Mutex()) {} }
        withTimeout(1000) { entered.await() }; job.cancelAndJoin()
        release.complete(Unit)
        var snapshot = HerrinSourceDiscovery.Snapshot("", 0)
        service.search("r", listOf(node), null, Mutex()) { snapshot = it }
        assertEquals(1, searches); assertEquals(2, loads)
        assertEquals(2, snapshot.routes.size)
    }

    @Test fun slowPreferredDetailsNeverOccupySearchLane() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val fallbackSearched = CompletableDeferred<Unit>()
        val first = HerrinSourceDiscovery.Node("preferred", "CMS", HerrinSourceType.TVBOX_CMS,
            { listOf(offer("a")) }, { release.await(); detail(it, 1) })
        val fallback = HerrinSourceDiscovery.Node("fallback", "CMS", HerrinSourceType.TVBOX_CMS,
            { fallbackSearched.complete(Unit); emptyList() }, { null })
        val job = launch { HerrinSourceDiscovery().search("r", listOf(first, fallback), "preferred", Mutex()) {} }
        withTimeout(1000) { fallbackSearched.await() }
        assertFalse(job.isCompleted)
        release.complete(Unit); job.join()
    }

    @Test fun cmsDetailsRemainAvailableWhileSpiderLaneIsPaused() = runBlocking {
        val service = HerrinSourceDiscovery().apply { spiderPaused = true }
        val cmsReady = CompletableDeferred<Unit>()
        var spiderCalls = 0
        val cms = HerrinSourceDiscovery.Node("cms", "CMS", HerrinSourceType.TVBOX_CMS,
            { listOf(offer("a")) }, { detail(it, 1) })
        val spider = HerrinSourceDiscovery.Node("spider", "Spider", HerrinSourceType.TVBOX_SPIDER,
            { spiderCalls++; emptyList() }, { null })
        val job = launch { service.search("r", listOf(cms, spider), null, Mutex()) {
            if (it.routes.isNotEmpty()) cmsReady.complete(Unit)
        } }
        withTimeout(1000) { cmsReady.await() }
        assertEquals(0, spiderCalls)
        service.spiderPaused = false
        withTimeout(1000) { job.join() }
        assertEquals(1, spiderCalls)
    }

    @Test fun cachedLayoutRebindsCallbacksToTheCurrentAuthorizedNode() = runBlocking {
        var searches = 0
        val original = HerrinSourceDiscovery.Node("node", "CMS", HerrinSourceType.TVBOX_CMS,
            { searches++; listOf(offer("a")) }, { detail(it, 1) })
        val service = HerrinSourceDiscovery()
        service.search("r", listOf(original), null, Mutex()) {}
        var currentDetails = 0
        val replacement = HerrinSourceDiscovery.Node("node", "CMS", HerrinSourceType.TVBOX_CMS,
            { error("Valid terminal searches must be reused") }, { currentDetails++; detail(it, 1) })
        var snapshot = HerrinSourceDiscovery.Snapshot("", 0)
        service.search("r", listOf(replacement), null, Mutex()) { snapshot = it }
        val hit = snapshot.hits.single()
        assertSame(replacement, hit.node)
        hit.node.details(hit.movie)
        assertEquals(1, currentDetails)
        assertEquals(1, searches)
        assertEquals(2, snapshot.routes.size)
    }

    @Test fun firstSpiderLayoutDoesNotQueueBehindEveryRemainingSearch() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val fast = HerrinSourceDiscovery.Node("first", "Spider", HerrinSourceType.TVBOX_SPIDER,
            { delay(10); listOf(offer("a")) }, { detail(it, 1) })
        val slow = HerrinSourceDiscovery.Node("slow", "Spider", HerrinSourceType.TVBOX_SPIDER,
            { release.await(); emptyList() }, { null })
        val job = launch { HerrinSourceDiscovery().search("r", listOf(fast, slow), null, Mutex()) {
            if (it.routes.isNotEmpty()) ready.complete(Unit)
        } }
        withTimeout(1000) { ready.await() }
        assertFalse(job.isCompleted)
        release.complete(Unit); job.join()
    }

    @Test fun manyVersionsOnOneCmsCannotStarveAnotherNodesFirstLayout() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val many = HerrinSourceDiscovery.Node("many", "CMS", HerrinSourceType.TVBOX_CMS,
            { (1..20).map { offer("a$it") } }, { release.await(); detail(it, 1) })
        val fast = HerrinSourceDiscovery.Node("fast", "CMS", HerrinSourceType.TVBOX_CMS,
            { delay(10); listOf(offer("b")) }, { detail(it, 1) })
        val job = launch { HerrinSourceDiscovery().search("r", listOf(many, fast), null, Mutex()) {
            if (it.routes.any { it.nodeId == "fast" }) ready.complete(Unit)
        } }
        withTimeout(1000) { ready.await() }
        assertFalse(job.isCompleted)
        release.complete(Unit); job.join()
    }

    @Test fun bundledPersonalPlaylistIsNotRepeatedForEveryMatchedFile() = runBlocking {
        var loads = 0
        val node = HerrinSourceDiscovery.Node("personal", "NAS", HerrinSourceType.NAS,
            { listOf(offer("a"), offer("b")) }, { loads++; detail(it, 1) }, bundledPersonalLayout = true)
        val service = HerrinSourceDiscovery()
        var snapshot = HerrinSourceDiscovery.Snapshot("", 0)
        service.search("r", listOf(node), null, Mutex()) { snapshot = it }
        service.search("r", listOf(node), null, Mutex()) { snapshot = it }
        assertEquals(2, snapshot.hits.size)
        assertEquals(2, snapshot.routes.size)
        assertEquals(1, loads)
    }
}
