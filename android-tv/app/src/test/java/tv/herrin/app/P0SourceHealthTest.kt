package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class P0SourceHealthTest {
    @Test fun indexPersistsOnlyOpaqueIdsAndNumbersAndNewConfigurationInvalidatesIt(){
        val file=Files.createTempDirectory("source-health").resolve("health.json").toFile()
        var now=1_000_000L
        val id="https://private.invalid/?Cookie=private-secret"
        val first=SourceHealthIndex(file,"authorization-a"){now}
        first.record(id,SourceSearchStage.MEDIA,SourceSearchOutcome.VERIFIED,120)
        first.save()
        assertFalse(file.readText().contains("private"));assertFalse(file.readText().contains("authorization-a"))
        assertTrue(SourceHealthIndex(file,"authorization-a"){now}.rank(id)>0)
        assertEquals(0L,SourceHealthIndex(file,"authorization-b"){now}.rank(id))
        now+=7*24*3600_000L+1
        assertEquals(0L,SourceHealthIndex(file,"authorization-a"){now}.rank(id))
        file.parentFile.deleteRecursively()
    }
    @Test fun retryAfterAuthAndExpiryHaveDifferentTemporaryPolicies(){
        var now=1_000_000L
        val index=SourceHealthIndex(null,"scope"){now}
        for(code in listOf(401,403,404,410,429)){
            val id="node-$code"
            index.record(id,SourceSearchStage.MEDIA,SourceHealthIndex.httpOutcome(code),10,90_000)
            assertFalse(index.eligible(id))
        }
        now+=90_001
        assertTrue(index.eligible("node-429"));assertTrue(index.eligible("node-404"));assertTrue(index.eligible("node-410"))
        assertFalse(index.eligible("node-401"));assertFalse(index.eligible("node-403"))
        now+=300_000;assertTrue(index.eligible("node-401"))
        assertEquals(90_000L,SourceHealthIndex.retryAfter("90",now))
        assertEquals(SourceSearchOutcome.EXPIRED,SourceHealthIndex.httpOutcome(410))
    }
    @Test fun repeatedTimeoutsBackOffButEventuallyRecoverAndSuccessPromotesEntry(){
        var now=1_000_000L
        val index=SourceHealthIndex(null,"scope"){now}
        repeat(3){index.record("slow",SourceSearchStage.SEARCH,SourceSearchOutcome.TIMEOUT,1200);now+=1000}
        assertFalse(index.eligible("slow"));now+=130_000;assertTrue(index.eligible("slow"))
        index.record("fast",SourceSearchStage.MEDIA,SourceSearchOutcome.VERIFIED,60)
        assertTrue(index.rank("fast")>index.rank("slow"))
    }
    @Test fun continuationPlanDoesNotResortAsHealthChangesAndConfigurationEditRestarts(){
        val plans=SourceSearchPlans();var preferred="spider:n7"
        val sites=(1..12).map{TvBoxSite("node",true,key="n$it",spiderApi="csp_Fixture")}
        val first=plans.get("movie","v1",sites){if(it==preferred)100 else 0}
        assertTrue(first.second);assertEquals("n7",first.first.sites.first().key)
        preferred="spider:n8"
        val same=plans.get("movie","v1",sites){if(it==preferred)100 else 0}
        assertFalse(same.second);assertEquals(first.first.sites,same.first.sites)
        val edited=plans.get("movie","v2",sites){if(it==preferred)100 else 0}
        assertTrue(edited.second);assertEquals("n8",edited.first.sites.first().key)
    }
    @Test fun cachedRoutesAreHintsAndCannotAuthorizeExpiredOrDifferentSpiderSessions(){
        var now=1000L
        val store=HerrinTmdbPlaybackRouteStore{now}
        store.spider("tmdb-tv-1","first")
        assertNotNull(store.usable("tmdb-tv-1","first"));assertNull(store.usable("tmdb-tv-1","second"))
        now+=60_001;assertNull(store.usable("tmdb-tv-1","first"))
        assertNotNull(store.forFilm("tmdb-tv-1")) // locator survives only as a reacquisition hint
        store.clear();assertNull(store.forFilm("tmdb-tv-1"))
    }
}
