package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class P0FastSourceSearchTest {
    private val request=HerrinFilmIdentity(550,"movie",listOf("合成电影","Original Movie"),2025)
    private fun site(n:Int)=TvBoxSite("node-$n",true,key="n$n",spiderApi="csp_Fixture")
    private fun movie()=Film("fixture","合成电影","","电影","2025","",0,playbackEpisodes=listOf(
        PlaybackEpisode(1,"正片",listOf(PlaybackLine("line","1080p","https://media.invalid/movie.mp4")))))
    private fun offer(site:TvBoxSite)=HerrinSourceMovie("spider:"+site.key,HerrinSourceType.TVBOX_SPIDER,movie(),"movie")
    private open inner class Session(private val hit:Int):HerrinApprovedSpiderBatch.Session {
        val activations=mutableListOf<String>()
        override suspend fun activate(site:TvBoxSite):Boolean{activations+=site.key;return true}
        override suspend fun search(site:TvBoxSite,identity:HerrinFilmIdentity)=if(site.key=="n$hit")listOf(offer(site)) else emptyList()
        override suspend fun details(candidate:HerrinSourceMovie):Film?=candidate.film
        override suspend fun canPlay(detailed:Film)=true
    }
    @Test fun sixTwelveAndFortyFiveNodesReachFirstThirdSeventhAndEighthWithoutQualityWait()=runBlocking {
        for(count in listOf(6,12,45)) for(hit in listOf(1,3,7,8).filter{it<=count}){
            val session=Session(hit)
            val result=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..count).map(::site),{true},session,
                stopAtFirstPlayable=true,maxWallClockMs=2000)
            assertEquals("n$hit",result.match?.site?.key)
            assertEquals(hit,result.checked)
            assertEquals(hit,session.activations.size)
            assertEquals("line",result.match?.line)
        }
    }
    @Test fun sixthHangingSearchTimesOutAndSeventhStartsWithRealCompletionProgress()=runBlocking {
        val starts=mutableListOf<Int>();val completed=mutableListOf<Int>()
        val session=object:Session(7){override suspend fun search(site:TvBoxSite,identity:HerrinFilmIdentity):List<HerrinSourceMovie>{
            if(site.key=="n6")awaitCancellation();return super.search(site,identity)
        }}
        val result=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..12).map(::site),{true},session,
            stopAtFirstPlayable=true,stageBudgetMs=25,maxWallClockMs=1000,onStageProgress={
                it.activeIndex?.let(starts::add)
                if(it.activeIndex==null)completed+=it.checked
                if(it.activeIndex==6)assertEquals(5,it.checked)
            })
        assertTrue(starts.contains(7));assertEquals(7,result.checked);assertEquals("n7",result.match?.site?.key)
        assertTrue(completed.contains(6))
    }
    @Test fun exhaustedForegroundBudgetResumesAtUnfinishedNodeNotAtNextPage()=runBlocking {
        val active=AtomicInteger()
        val session=object:Session(8){override suspend fun search(site:TvBoxSite,identity:HerrinFilmIdentity):List<HerrinSourceMovie>{
            active.incrementAndGet();try{delay(20);return super.search(site,identity)}finally{active.decrementAndGet()}
        }}
        val first=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..12).map(::site),{true},session,
            maxWallClockMs=75,stopAtFirstPlayable=true,stageBudgetMs=100)
        assertNull(first.match);assertTrue(first.nextOffset in 0..7)
        assertEquals(first.checked,first.nextOffset);assertEquals(0,active.get())
        val resumed=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..12).map(::site),{true},session,
            offset=first.nextOffset,maxWallClockMs=1000,stopAtFirstPlayable=true,stageBudgetMs=100)
        assertEquals("n8",resumed.match?.site?.key);assertEquals(8,first.checked+resumed.checked)
    }
    @Test fun sixDeferredNodesDoNotHideASeventhAuthorizedNodeOrInventChecks()=runBlocking {
        val session=object:Session(7){override suspend fun eligible(site:TvBoxSite)=site.key=="n7"}
        val result=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..12).map(::site),{true},session,stopAtFirstPlayable=true)
        assertEquals("n7",result.match?.site?.key);assertEquals(1,result.checked);assertEquals(listOf("n7"),session.activations)
    }
    @Test fun completedHttpCandidateDoesNotWaitForItsHangingGroupPeer()=runBlocking {
        val active=AtomicInteger()
        val sources=(1..12).map{n->object:HerrinFilmSource{
            override val id="cms:$n";override val type=HerrinSourceType.TVBOX_CMS
            override suspend fun search(identity:HerrinFilmIdentity):List<HerrinSourceMovie>{
                active.incrementAndGet();try{
                    if(n==1)delay(15) else awaitCancellation()
                    return listOf(HerrinSourceMovie(id,type,movie(),"movie"))
                }finally{active.decrementAndGet()}
            }
        }}
        val result=withTimeout(700){HerrinFilmRouter(sources).find(request,streaming=true){it}}
        assertEquals("cms:1",result.options.single().sourceId);assertEquals(0,active.get())
    }
    @Test fun firstRealVerified1080StopsBeforeOtherNodesAndWrongFilmIsNeverVerified()=runBlocking {
        var probes=0
        val session=object:Session(1){
            override suspend fun search(site:TvBoxSite,identity:HerrinFilmIdentity)=listOf(
                offer(site).copy(film=movie().copy(title="另一部电影")),offer(site))
            override suspend fun canPlay(detailed:Film):Boolean{probes++;return true}
        }
        val result=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..45).map(::site),{true},session,stopAtFirstPlayable=true)
        assertEquals(1,probes);assertEquals(1,result.checked);assertEquals(1080,result.match?.claim)
    }
    @Test fun nodeSevenTimeoutStillAllowsEighthAndExternalCancellationDrainsTasks()=runBlocking {
        val session=object:Session(8){override suspend fun activate(site:TvBoxSite):Boolean{
            if(site.key=="n7")awaitCancellation();return super.activate(site)
        }}
        val result=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..45).map(::site),{true},session,stageBudgetMs=20,stopAtFirstPlayable=true)
        assertEquals("n8",result.match?.site?.key)
        val active=AtomicInteger()
        val job=launch{HerrinApprovedSpiderBatch.scanAcrossPages(request,listOf(site(1)),{true},object:Session(0){
            override suspend fun activate(site:TvBoxSite):Boolean{
                active.incrementAndGet();try{awaitCancellation()}finally{active.decrementAndGet()}
            }
        },stopAtFirstPlayable=true)}
        while(active.get()==0)yield()
        job.cancelAndJoin();assertEquals(0,active.get())
    }
    @Test fun realEightAndFifteenSecondBudgetsLeaveNoCooperativeWorkAndPreserveCursor()=runBlocking {
        val active=AtomicInteger()
        val session=object:Session(0){override suspend fun search(site:TvBoxSite,identity:HerrinFilmIdentity):List<HerrinSourceMovie>{
            active.incrementAndGet();try{delay(250);return emptyList()}finally{active.decrementAndGet()}
        }}
        var offset=0
        for(budget in listOf(SourceSearchPolicy.FOREGROUND_MS,SourceSearchPolicy.EXTENDED_MS)){
            val start=System.nanoTime()
            val scan=HerrinApprovedSpiderBatch.scanAcrossPages(request,(1..100).map(::site),{true},session,
                offset=offset,maxWallClockMs=budget,stageBudgetMs=SourceSearchPolicy.STAGE_MS,stopAtFirstPlayable=true)
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertTrue("budget=$budget elapsed=$elapsed",elapsed in (budget-100)..(budget+2000))
            assertNull(scan.match);assertEquals(offset+scan.checked,scan.nextOffset)
            assertEquals(0,active.get());offset=scan.nextOffset
        }
    }
    @Test fun onlyFinishedValidationMarksNodeCompleteAndSerialSpiderNeverOverlaps()=runBlocking {
        val events=mutableListOf<SourceSearchEvent>();var validating=false
        val providers=(1..3).map{n->object:HerrinFilmSource{
            override val id="spider:$n";override val type=HerrinSourceType.TVBOX_SPIDER
            override suspend fun search(identity:HerrinFilmIdentity):List<HerrinSourceMovie>{
                assertFalse(validating);return listOf(HerrinSourceMovie(id,type,movie(),"movie"))
            }
        }}
        HerrinFilmRouter(providers).find(request,streaming=true,onEvent={events+=it}){offers->
            validating=true;delay(10)
            assertFalse(events.any{it.source==offers.first().sourceId &&it.nodeComplete})
            validating=false;emptyList()
        }
        assertEquals(listOf(1,2,3),events.filter{it.nodeComplete}.map{it.completed})
    }
    @Test fun authFailureDoesNotTryOtherVodCandidatesOnSameSource()=runBlocking {
        var probes=0
        val provider=object:HerrinFilmSource{
            override val id="cms:fixture";override val type=HerrinSourceType.TVBOX_CMS
            override suspend fun search(identity:HerrinFilmIdentity)=(1..3).map{n->
                HerrinSourceMovie(id,type,movie().copy(id="vod$n",cmsVodId="$n"),"movie")}
        }
        val result=HerrinVerifiedSourceResolver(listOf(provider)).find(request,personalPlayback={PlaybackResult.Unavailable("unused")},
            streaming=true,verifyRemote={_,_->probes++;throw SourceHttpFailure(401)},remoteDetails={it.film})
        assertTrue(result.lookup.options.isEmpty());assertEquals(1,probes)
    }
}
