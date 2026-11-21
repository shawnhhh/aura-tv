package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class NextDiscoveryCandidateTest {
    private fun hit(id: String, ready: Boolean = false, relevance: SourceRelevance = SourceRelevance.CONFIRMED): HerrinSourceDiscovery.Hit {
        val node = HerrinSourceDiscovery.Node(id, id, HerrinSourceType.TVBOX_CMS,
            { error("Candidate choice must not execute providers") }, { error("Candidate choice must not parse media") })
        val movie = HerrinSourceMovie(id, HerrinSourceType.TVBOX_CMS,
            Film(id, "Fixture", "", "电影", "2026", "", 1, cmsVodId = id), "movie")
        return HerrinSourceDiscovery.Hit(id, node, movie,
            if (ready) listOf(HerrinSourceDiscovery.EpisodeLayout(1, "正片", listOf("one" to "original"))) else emptyList(), relevance)
    }
    @Test fun failedQuickAttemptUsesReadyConfirmedCandidateThenPendingConfirmedCandidate() {
        val state = TmdbScreenState()
        state.discoveredSources["film"] = HerrinSourceDiscovery.Snapshot("r", 3, hits = listOf(
            hit("possible", ready = true, relevance = SourceRelevance.POSSIBLE), hit("pending"), hit("ready", ready = true)))
        assertEquals("ready", state.nextDiscoveryHit("film")?.key)
        assertEquals("pending", state.nextDiscoveryHit("film", "ready")?.key)
        assertNull(state.nextDiscoveryHit("film", "pending"))
    }
    @Test fun explicitQuickDiscoveryBudgetCannotExpandToEveryConfiguredCandidate() {
        val state = TmdbScreenState()
        state.discoveredSources["film"] = HerrinSourceDiscovery.Snapshot("r", 20, hits = (0..19).map { hit("candidate-$it") })
        val attempted = mutableSetOf<String>()
        repeat(12) {
            val candidate = state.nextDiscoveryHit("film")!!
            assertTrue(attempted.add(candidate.key))
            state.nextDiscoveryHit("film", candidate.key)
        }
        assertEquals(12, attempted.size)
        assertNull(state.nextDiscoveryHit("film"))
    }
}
