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
    @Test fun continuationSkipsCompletedNodesEvenIfUiCursorWasReset() = runBlocking {
        val completed=mutableSetOf<String>()
        val session=object : Session() {
            override suspend fun eligible(site:TvBoxSite)= "spider:"+site.key !in completed
        }
        val events:suspend (SourceSearchEvent)->Unit={if(it.nodeComplete)completed.add(it.source)}
        val first=HerrinApprovedSpiderBatch.scanAcrossPages(identity,(1..7).map(::site),{true},session,
            maxPages=1,stopAtFirstPlayable=true,onEvent=events)
        assertEquals(6,first.checked)
        val second=HerrinApprovedSpiderBatch.scanAcrossPages(identity,(1..7).map(::site),{true},session,
            offset=0,stopAtFirstPlayable=true,onEvent=events)
        assertEquals(1,second.checked)
        assertEquals((1..7).map{"n$it"},session.visited)
    }
    @Test fun progressCountsOnlyMediaVerificationAsSuccessAndDoesNotDoubleCount() {
        val plans=SourceSearchPlans()
        plans.get("film","revision",emptyList()){0}
        plans.register("film","revision",listOf("a","b","c","d"))
        plans.observe("film","revision",SourceSearchEvent("a",SourceSearchStage.SEARCH,SourceSearchOutcome.VERIFIED))
        assertEquals(0,plans.progress("film","revision").success)
        val done=SourceSearchEvent("a",SourceSearchStage.MEDIA,SourceSearchOutcome.VERIFIED,nodeComplete=true)
        plans.observe("film","revision",done);plans.observe("film","revision",done)
        plans.observe("film","revision",SourceSearchEvent("b",SourceSearchStage.ACTIVATE,SourceSearchOutcome.TIMEOUT,nodeComplete=true))
        plans.observe("film","revision",SourceSearchEvent("c",SourceSearchStage.SEARCH,SourceSearchOutcome.NO_MATCH,nodeComplete=true))
        val progress=plans.progress("film","revision")
        assertEquals(SourceSearchPlans.Progress(4,3,1,1,1),progress)
        assertEquals(1,progress.remaining)
    }

    @Test fun remainingPlanSurvivesResumeUntilExplicitRetryEvenWithZeroUiOffset() {
        val plans=SourceSearchPlans()
        val first=plans.get("film","revision",(1..7).map(::site)){0}.first
        plans.register("film","revision",(1..7).map{"spider:n$it"})
        plans.complete("film","revision","spider:n1")
        assertTrue(plans.hasRemaining("film"))
        val resumed=plans.get("film","revision",(1..7).map(::site)){100}.first
        assertSame(first,resumed)
        assertEquals(setOf("spider:n1"),plans.completed("film","revision"))
        for(n in 2..7)plans.complete("film","revision","spider:n$n")
        assertFalse(plans.hasRemaining("film"))
        plans.reset("film")
        assertTrue(plans.get("film","revision",(1..7).map(::site)){0}.second)
        assertTrue(plans.completed("film","revision").isEmpty())
    }

}
