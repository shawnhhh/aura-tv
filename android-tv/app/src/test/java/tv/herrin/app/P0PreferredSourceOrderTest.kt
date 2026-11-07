package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scheduling fixtures only, not third-party playback evidence. */
class P0PreferredSourceOrderTest {
    private val identity = HerrinFilmIdentity(42, "movie", listOf("Example"), 2025)
    private fun source(id: String, type: HerrinSourceType, calls: MutableList<String>, hit: Boolean = true) = object : HerrinFilmSource {
        override val id = id
        override val type = type
        override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
            calls += id
            return if (!hit) emptyList() else listOf(HerrinSourceMovie(id, type,
                Film(id, "Example", "", "电影", "2025", "", 0), "movie"))
        }
    }
    @Test fun personalThenPreferredSpiderBeforeAnyCms() = runBlocking {
        val calls = mutableListOf<String>()
        val sources = listOf(source("nas", HerrinSourceType.NAS, calls, false),
            source("cms", HerrinSourceType.TVBOX_CMS, calls), source("chosen", HerrinSourceType.TVBOX_SPIDER, calls))
        val result = HerrinFilmRouter(sources, "chosen").find(identity, streaming = true) { it }
        assertEquals(listOf("nas", "chosen"), calls)
        assertEquals("chosen", result.options.single().sourceId)
    }
    @Test fun rejectedPreferredCandidateFallsBackOnlyAfterVerification() = runBlocking {
        val calls = mutableListOf<String>()
        val sources = listOf(source("backup", HerrinSourceType.TVBOX_CMS, calls), source("chosen", HerrinSourceType.TVBOX_CMS, calls))
        val result = HerrinFilmRouter(sources, "chosen").find(identity, streaming = true) {
            calls += "verify:" + it.first().sourceId
            if (it.first().sourceId == "chosen") emptyList() else it
        }
        assertEquals(listOf("chosen", "verify:chosen", "backup", "verify:backup"), calls)
        assertEquals("backup", result.options.single().sourceId)
    }
    @Test fun missingPreferenceDoesNotExecuteUnrelatedSpider() = runBlocking {
        val calls = mutableListOf<String>()
        val result = HerrinFilmRouter(listOf(source("cms", HerrinSourceType.TVBOX_CMS, calls)), "removed")
            .find(identity, streaming = true) { it }
        assertEquals(listOf("cms"), calls)
        assertEquals("cms", result.options.single().sourceId)
    }
    @Test fun timedOutSpiderPublishesCompletionAndAllowsBackup() = runBlocking {
        val calls = mutableListOf<String>()
        val hanging = object : HerrinFilmSource {
            override val id = "chosen"
            override val type = HerrinSourceType.TVBOX_SPIDER
            override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> = awaitCancellation()
        }
        val events = mutableListOf<SourceSearchEvent>()
        val result = withTimeout(4_000) {
            HerrinFilmRouter(listOf(hanging, source("backup", HerrinSourceType.TVBOX_CMS, calls)), "chosen")
                .find(identity, streaming = true, onEvent = { events += it }) { it }
        }
        assertEquals("backup", result.options.single().sourceId)
        assertTrue(events.any { it.source == "chosen" && it.nodeComplete && it.outcome == SourceSearchOutcome.TIMEOUT })
    }
    @Test fun deadlineDuringCmsValidationIsCompletedForContinuationCursor() = runBlocking {
        val calls = mutableListOf<String>()
        val events = mutableListOf<SourceSearchEvent>()
        val result = withTimeout(2_500) {
            HerrinFilmRouter(listOf(source("cms", HerrinSourceType.TVBOX_CMS, calls)))
                .find(identity, streaming = true, onEvent = { events += it }) { awaitCancellation() }
        }
        assertTrue(result.options.isEmpty())
        assertTrue(events.any { it.source == "cms" && it.nodeComplete && it.outcome == SourceSearchOutcome.TIMEOUT })
        assertEquals(listOf("cms"), result.searchedSources)
    }

    @Test fun explicitPreferenceOutranksHealthCooldownAndSuccessfulBackup() = runBlocking {
        val health = SourceHealthIndex(null, "fixture", clock = { 100_000L })
        health.record("chosen", SourceSearchStage.SEARCH, SourceSearchOutcome.TIMEOUT, 1000)
        health.record("backup", SourceSearchStage.MEDIA, SourceSearchOutcome.VERIFIED, 10)
        assertFalse(health.eligible("chosen"))
        val calls = mutableListOf<String>()
        val result = HerrinFilmRouter(listOf(source("backup", HerrinSourceType.TVBOX_CMS, calls),
            source("chosen", HerrinSourceType.TVBOX_CMS, calls)), "chosen")
            .find(identity, streaming = true, health = health) { it }
        assertEquals(listOf("chosen"), calls)
        assertEquals("chosen", result.options.single().sourceId)
    }

}
