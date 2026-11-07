package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic scheduler reproductions; not evidence about a third-party JAR. */
class P0NodeRecoveryTest {
    private val identity = HerrinFilmIdentity(1, "movie", listOf("Recovery"), 2025)
    private fun site(n: Int) = TvBoxSite("Node $n", true, key="n$n", spiderApi="csp_Fixture")
    private open class Session : HerrinApprovedSpiderBatch.Session {
        val visited = mutableListOf<String>()
        override suspend fun activate(site: TvBoxSite): Boolean { visited += site.key; return true }
        override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) = emptyList<HerrinSourceMovie>()
        override suspend fun details(candidate: HerrinSourceMovie): Film? = null
        override suspend fun canPlay(detailed: Film) = false
    }
    @Test fun everyTerminalNodePublishesOneCompletionForThePlan() = runBlocking {
        val events = mutableListOf<SourceSearchEvent>()
        val result = HerrinApprovedSpiderBatch.scanAcrossPages(identity, (1..7).map(::site), { true },
            object : Session() { override suspend fun activate(site: TvBoxSite): Boolean {
                super.activate(site); if(site.key=="n2") throw IllegalStateException("fixture failure"); return site.key!="n1"
            } }, stopAtFirstPlayable=true, onEvent={ events += it })
        assertEquals(7, result.checked)
        assertEquals(7, events.count { it.nodeComplete })
        assertEquals(7, events.filter { it.nodeComplete }.map { it.source }.distinct().size)
    }
    @Test fun coldActivationGetsItsOwnBudgetInsteadOfSearchDeadline() = runBlocking {
        val session = object : Session() { override suspend fun activate(site: TvBoxSite): Boolean {
            delay(60); return super.activate(site)
        } }
        val scan = HerrinApprovedSpiderBatch.scanAcrossPages(identity, listOf(site(1)), { true }, session,
            stopAtFirstPlayable=true, stageBudgetMs=20, activationBudgetMs=150, maxWallClockMs=500)
        assertEquals(listOf("n1"), session.visited)
        assertEquals(HerrinApprovedSpiderBatch.Outcome.NO_MATCH, scan.lastOutcome)
    }
}
