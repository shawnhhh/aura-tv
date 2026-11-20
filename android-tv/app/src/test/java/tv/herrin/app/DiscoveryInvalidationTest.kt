package tv.herrin.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class DiscoveryInvalidationTest {
    @Test fun failedLayoutDisappearsAndTargetedRetryKeepsOtherNodesAndStableRouteOrdinals() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        val detailCalls = mutableMapOf<String, Int>()
        var changed = false
        fun node(id: String) = HerrinSourceDiscovery.Node(id, id, HerrinSourceType.TVBOX_CMS, {
            calls[id] = (calls[id] ?: 0) + 1
            listOf(HerrinSourceMovie(id, HerrinSourceType.TVBOX_CMS,
                Film(id, "Fixture", "", "电影", "2026", "", 1, cmsVodId = id), "movie"))
        }, { hit ->
            detailCalls[id] = (detailCalls[id] ?: 0) + 1
            val ids = if (changed && id == "a") listOf("two", "three") else listOf("one", "two")
            HerrinSourceDiscovery.Detail(hit.film.copy(playbackEpisodes = listOf(PlaybackEpisode(1, "正片",
                ids.map { PlaybackLine(it, "duplicate name", "https://example.invalid/$id/$it") }))))
        })
        val service = HerrinSourceDiscovery()
        val nodes = listOf(node("a"), node("b"))
        var snapshot = HerrinSourceDiscovery.Snapshot("", 0)
        service.search("r", nodes, null, Mutex()) { snapshot = it }
        val original = snapshot.routes.filter { it.nodeId == "a" }
        snapshot = service.invalidateLayout("r", "a:a")!!
        assertTrue(snapshot.readyRoutes.none { it.nodeId == "a" })
        assertEquals(2, snapshot.readyRoutes.count { it.nodeId == "b" })
        assertTrue("The failed selected node must offer its own retry", "a:a" in snapshot.failedDetails)
        assertFalse("The healthy sibling must not become a failed retry target", "b:b" in snapshot.failedDetails)
        // Returning to the page resumes discovery, but only the explicit
        // retry action may reauthorize/reload a failed node.
        service.search("r", nodes, null, Mutex()) { snapshot = it }
        assertEquals(mapOf("a" to 1, "b" to 1), detailCalls)
        assertTrue("a:a" in snapshot.failedDetails)
        assertTrue(snapshot.readyRoutes.none { it.nodeId == "a" })
        changed = true
        service.resetNode("r", "a")
        service.search("r", nodes, null, Mutex()) { snapshot = it }
        assertEquals(2, calls["a"])
        assertEquals(1, calls["b"])
        assertEquals(mapOf("a" to 2, "b" to 1), detailCalls)
        assertTrue("A successful retry clears only the recovered failure", snapshot.failedDetails.isEmpty())
        val all = snapshot.routes.filter { it.nodeId == "a" }
        assertEquals(original, all.take(2))
        val visible = all.withIndex().filter { it.value in snapshot.readyRoutes }
        assertEquals(listOf(1, 2), visible.map { it.index }) // UI labels remain 线路2、线路3.
        assertEquals(listOf("two", "three"), visible.map { it.value.lineId })
    }
}
