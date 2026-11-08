package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Protocol fixtures exercise real HTTP/JSON, not a claim about a third-party source. */
class P0AggregatedDiscoveryTest {
    private val identity = HerrinFilmIdentity(99999, "tv", listOf("小城良方"), 2026)
    private fun offer(id: String, type: HerrinSourceType = HerrinSourceType.TVBOX_CMS) =
        HerrinSourceMovie(id, type, Film("provider-$id", "小城良方（国语）", "更新", "剧集", "2025", "",
            R.drawable.photofilm_orbit, cmsVodId = if(type == HerrinSourceType.TVBOX_CMS) "local-7" else null,
            spiderVodId = if(type == HerrinSourceType.TVBOX_SPIDER) "opaque-7" else null), "tv")
    private fun node(id: String, type: HerrinSourceType = HerrinSourceType.TVBOX_CMS,
        timeout: Long = 1000, search: suspend () -> List<HerrinSourceMovie> = { listOf(offer(id, type)) }) =
        HerrinSourceDiscovery.Node(id, id, type, search, { error("Discovery must never request details or media") }, timeoutMs = timeout)

    @Test fun sameTitleFromMultipleSitesSurvivesUnavailableMedia() = runBlocking {
        var last = HerrinSourceDiscovery.Snapshot("",0)
        HerrinSourceDiscovery().search("r", listOf(node("a"),node("b")), null, Mutex()) { last = it }
        assertEquals(2, last.hits.size)
        assertEquals(2, last.hits.map { it.key }.distinct().size)
        assertTrue(last.hits.all { it.movie.film.playbackEpisodes.isEmpty() })
        assertEquals(2,last.checked)
    }
    @Test fun personalThenExplicitPreferredThenFallbackRegardlessOfConfiguredOrder() = runBlocking {
        val order = mutableListOf<String>()
        fun source(id:String,type:HerrinSourceType) = node(id,type) { order += id; emptyList() }
        HerrinSourceDiscovery().search("r", listOf(source("fallback",HerrinSourceType.TVBOX_CMS),
            source("preferred",HerrinSourceType.TVBOX_SPIDER),source("nas",HerrinSourceType.NAS)),
            "preferred",Mutex()) {}
        assertEquals(listOf("nas","preferred","fallback"),order)
    }
    @Test fun oneTimeoutDoesNotHideFastHitsAndCountersAreTerminal() = runBlocking {
        val hang = node("hang", timeout = 30) { awaitCancellation() }
        val snapshots = mutableListOf<HerrinSourceDiscovery.Snapshot>()
        HerrinSourceDiscovery().search("r",listOf(hang,node("fast")),null,Mutex()) { snapshots += it }
        val last = snapshots.last()
        assertEquals(HerrinSourceDiscovery.Outcome.TIMEOUT,last.outcomes["hang"])
        assertEquals(1,last.hits.size)
        assertEquals(2,last.checked)
        assertEquals(0,last.remaining)
        assertTrue(snapshots.any { it.hits.isNotEmpty() && it.checked < 2 })
    }
    @Test fun fortyFourSitesCancelAndContinueWithoutRepeatingCompletedNodesOrLosingHits() = runBlocking {
        val calls = IntArray(44)
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val sources = (0 until 44).map { n -> node("n$n",timeout=3000) {
            calls[n]++
            if(n != 0) release.await()
            listOf(offer("n$n"))
        } }
        val service = HerrinSourceDiscovery()
        var last = HerrinSourceDiscovery.Snapshot("",0)
        val first = launch { service.search("r",sources,null,Mutex()) {
            last=it
            if(it.hits.isNotEmpty())ready.complete(Unit)
        } }
        withTimeout(1000){ ready.await() }
        first.cancelAndJoin()
        assertEquals(1,last.hits.size)
        release.complete(Unit)
        service.search("r",sources,null,Mutex()) { last=it }
        assertEquals(44,last.checked)
        assertEquals(44,last.hits.size)
        assertEquals(1,calls[0])
    }
    @Test fun cmsConcurrencyIsBoundedAndSpiderLifecycleIsSerial() = runBlocking {
        val cms = AtomicInteger(); val spider = AtomicInteger()
        val cmsMax=AtomicInteger(); val spiderMax=AtomicInteger()
        val nodes=(0 until 44).map { n ->
            val isSpider = n % 2 == 0
            node("n$n",if(isSpider)HerrinSourceType.TVBOX_SPIDER else HerrinSourceType.TVBOX_CMS) {
                val current=if(isSpider)spider else cms
                val maximum=if(isSpider)spiderMax else cmsMax
                val active = current.incrementAndGet()
                maximum.updateAndGet{ maxOf(it,active) }
                try { delay(5); listOf(offer("n$n")) } finally { current.decrementAndGet() }
            }
        }
        var last=HerrinSourceDiscovery.Snapshot("",0)
        HerrinSourceDiscovery().search("r",nodes,null,Mutex()) {last=it}
        assertEquals(44,last.checked)
        assertEquals(1,spiderMax.get())
        assertTrue(cmsMax.get() in 2..4)
    }
    @Test fun failedDetailsDoNotMutateOtherDiscoveredSources() = runBlocking {
        val service=HerrinSourceDiscovery()
        var last=HerrinSourceDiscovery.Snapshot("",0)
        service.search("r",listOf(node("a"),node("b")),null,Mutex()){last=it}
        runCatching{last.hits.first().node.details(last.hits.first().movie)}
        service.search("r",listOf(node("a"),node("b")),null,Mutex()){last=it}
        assertEquals(listOf("a","b"),last.hits.map{it.node.id})
    }
    @Test fun configurationIdentityChangeInvalidatesOldHits() = runBlocking {
        val service=HerrinSourceDiscovery();var last=HerrinSourceDiscovery.Snapshot("",0)
        service.search("r1",listOf(node("a")),null,Mutex()){last=it}
        service.search("r2",listOf(node("b")),null,Mutex()){last=it}
        assertEquals(listOf("b"),last.hits.map{it.node.id})
    }
    @Test fun realCmsSearchKeepsAnnotatedTitleAndUsesProviderIdForDetails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"provider-123","vod_name":"小城良方（国语）","type_name":"电视剧","vod_year":"2024"}]}"""))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"provider-123","vod_name":"小城良方（国语）","vod_play_from":"A","vod_play_url":"第一集${'$'}https://example.invalid/unavailable.mp4"}]}"""))
            server.start()
            val site=TvBoxSite("CMS fixture",false,server.url("/api").toString(),key="fixture")
            val source=HerrinTvBoxCmsSource(site,TvBoxVodRepository(),true)
            val hit=source.searchCandidates(identity).single()
            assertEquals("小城良方",server.takeRequest().requestUrl?.queryParameter("wd"))
            assertEquals("provider-123",hit.film.cmsVodId)
            assertTrue(hit.film.playbackEpisodes.isEmpty())
            val detail=source.details(hit) as TvBoxVodDetailResult.Ready
            assertEquals("provider-123",server.takeRequest().requestUrl?.queryParameter("ids"))
            assertEquals(1,detail.film.playbackEpisodes.size)
            assertEquals(2,server.requestCount) // no media request in either stage
        }
    }
    @Test fun providerIdentityIncludesRootApiJarAndSiteKey() {
        val site=TvBoxSite("same label",true,key="a",spiderApi="csp_Fixture")
        val original=HerrinDiscoveryNodes.id("root",site,"jarA")
        assertNotEquals(original,HerrinDiscoveryNodes.id("root2",site,"jarA"))
        assertNotEquals(original,HerrinDiscoveryNodes.id("root",site.copy(key="b"),"jarA"))
        assertNotEquals(original,HerrinDiscoveryNodes.id("root",site,"jarB"))
    }
    @Test fun explicitWrongSpiderDetailVodCannotMixLines() {
        val original=offer("spider",HerrinSourceType.TVBOX_SPIDER).film
        val response="""{"list":[{"vod_id":"other-vod","vod_play_from":"wrong","vod_play_url":"集${'$'}wrong-id"}]}"""
        assertTrue(runCatching{SpiderVodCodec.detail(response,original)}.isFailure)
        val right="""{"list":[{"vod_id":"other"},{"vod_id":"opaque-7","vod_play_from":"right","vod_play_url":"集${'$'}right-id"}]}"""
        assertEquals("right-id",SpiderVodCodec.detail(right,original).playbackEpisodes.single().lines.single().uri)
    }
    @Test fun authorizationAndRateLimitKeepTheirActualStageAndErrorCode() = runBlocking {
        val events=mutableListOf<SourceSearchEvent>()
        var last=HerrinSourceDiscovery.Snapshot("",0)
        val activate=node("init",HerrinSourceType.TVBOX_SPIDER) {
            throw SourceCandidateFailure(SourceSearchStage.ACTIVATE,SourceSearchOutcome.ACTIVATE_FAILED)
        }
        val limited=node("429") { throw SourceHttpFailure(429,3000) }
        HerrinSourceDiscovery().search("r",listOf(activate,limited,node("ready")),null,Mutex(),{events+=it}){last=it}
        assertEquals(3,last.checked)
        assertEquals(1,last.hits.size)
        assertEquals(SourceSearchStage.ACTIVATE,events.single{it.source=="init"}.stage)
        assertEquals(SourceSearchOutcome.ACTIVATE_FAILED,events.single{it.source=="init"}.outcome)
        assertEquals(SourceSearchOutcome.RATE_LIMITED,events.single{it.source=="429"}.outcome)
        assertTrue(events.all{it.nodeComplete &&it.total==3})
    }
    @Test fun diagnosticNeverExportsPrivateAddressOrParameters() {
        DiscoveryProtocolTrace.record("https://private.invalid/api?token=private","CMS_SEARCH",3,1,
            "https://private.invalid/api?token=private")
        val line=DiscoveryProtocolTrace.summary().lineSequence().last()
        assertFalse(line.contains("private"))
        assertFalse(line.contains("https"))
        assertTrue(line.contains("raw=3 parsed=1 discarded=2"))
    }

}
