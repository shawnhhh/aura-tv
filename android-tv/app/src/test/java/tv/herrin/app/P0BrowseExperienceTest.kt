package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class P0BrowseExperienceTest {
    private fun film(id: String, category: String) = Film(id,id,"",category,"2026","",1)
    private val page = """{"results":[{"id":42,"title":"Original fixture","overview":"Synopsis","release_date":"2026-01-01","poster_path":"/p.jpg","backdrop_path":"/b.jpg","vote_count":10,"vote_average":8.1,"genre_ids":[28],"media_url":"https://private.test/?token=DO_NOT_SAVE"}]}"""

    @Test fun everyCategoryHeroUsesItsActualFirstRecommendationAndValidRememberedIdentity() {
        val categories = listOf("首页","电影","剧集","动漫","综艺","纪录片","Top 250")
        val catalog = categories.drop(1).mapIndexed { i,c -> film("$i",c) }
        val top = listOf(film("top", "电影"))
        (categories + listOf("首页","电影","剧集","电影")).forEach { category ->
            val candidates = BrowseHeroSelection.candidates(category,catalog,top)
            assertEquals(candidates.first().id, BrowseHeroSelection.select(candidates,"old-other-category")!!.id)
        }
        val home = BrowseHeroSelection.candidates("首页",catalog,top)
        assertEquals(home.last().id,BrowseHeroSelection.select(home,home.last().id)!!.id)
        assertNull(BrowseHeroSelection.select(emptyList(),"old"))
    }
    @Test fun heroUsesOnlyOriginalBackdropAndCardsDoNotDownloadOriginal() {
        val f=film("tmdb-movie-1","电影").copy(metadataSource="TMDB",artworkUrl="https://image.tmdb.org/t/p/w500/p.jpg",backdropUrl="https://image.tmdb.org/t/p/w1280/b.jpg")
        assertEquals("https://image.tmdb.org/t/p/original/b.jpg",ArtworkRequestPolicy.url(f,true))
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg",ArtworkRequestPolicy.url(f,false))
        assertNull(ArtworkRequestPolicy.url(f.copy(backdropUrl=null),true))
        assertEquals(f.artworkUrl,ArtworkRequestPolicy.url(f.copy(backdropUrl=null),false))
        assertEquals("https://private.test/a?size=w500",ArtworkRequestPolicy.url(f.copy(backdropUrl="https://private.test/a?size=w500"),true))
    }
    @Test fun coldStartCacheSurvivesOfflineAndIsPartitionedByLanguageAndConfig() = runBlocking {
        val dir=Files.createTempDirectory("tmdb-home").toFile()
        var now=1_000_000L
        val count=AtomicInteger()
        try {
            val first=HerrinTmdbDiscovery(ArtworkJsonFetcher { _,_->count.incrementAndGet();page },{now},dir)
            val section=first.sections.first()
            assertEquals("tmdb-movie-42",first.load(section,"approved-token").films.single().id)
            assertFalse(dir.walk().filter{it.isFile}.joinToString { it.name+it.readText() }.contains("DO_NOT_SAVE"))
            assertFalse(dir.walk().filter{it.isFile}.joinToString { it.name+it.readText() }.contains("approved-token"))
            val cold=HerrinTmdbDiscovery(ArtworkJsonFetcher { _,_->throw IOException() },{now},dir)
            assertEquals("tmdb-movie-42",cold.cachedHome("approved-token").single().id)
            assertEquals(1,cold.load(section,"approved-token").films.size)
            assertTrue(cold.cachedHome("different-config").isEmpty())
            assertTrue(HerrinTmdbDiscovery(cacheDir=dir,language="en-US").cachedHome("approved-token").isEmpty())
            first.invalidate("approved-token")
            assertEquals(1,first.cachedHome("approved-token").size)
            first.load(section,"approved-token")
            assertEquals(2,count.get())
            now+=7*3600_000
            assertEquals(1,cold.load(section,"approved-token").films.size)
            assertEquals(2,count.get())
            cold.clearPersistent();assertTrue(cold.cachedHome("approved-token").isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun concurrentIdenticalHomeRequestsAreDeduplicatedWithoutDiskCache() = runBlocking {
        val calls=AtomicInteger()
        val discovery=HerrinTmdbDiscovery(ArtworkJsonFetcher { _,_->calls.incrementAndGet();page })
        coroutineScope { List(8) { async { discovery.load(discovery.sections.first(),"token") } }.awaitAll() }
        assertEquals(1,calls.get())
    }
    @Test fun samePhysicalBackIsDispatchedOnceButNextPressIsAccepted() {
        val gate=PlaybackBackGate()
        assertTrue(gate.accept(100L,1000L));assertFalse(gate.accept(100L,1000L));assertFalse(gate.accept(null,1001L));assertTrue(gate.accept(101L,1001L))
    }
}
