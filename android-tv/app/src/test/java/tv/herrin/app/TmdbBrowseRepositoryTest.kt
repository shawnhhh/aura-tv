package tv.herrin.app

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class TmdbBrowseRepositoryTest {
    private val day = LocalDate.of(2026, 11, 9)
    private fun movie(vararg types: Pair<Int, String>) = JSONObject().put("id", 1).put("release_dates",
        JSONObject().put("results", JSONArray().put(JSONObject().put("release_dates", JSONArray().apply {
            types.forEach { (type, day) -> put(JSONObject().put("type", type).put("release_date", day)) }
        }))))
    @Test fun releaseProofDoesNotConfuseTheatricalFutureOrUnknownWithDigital() {
        assertEquals(TmdbAvailability.THEATRICAL_ONLY, TmdbReleaseEvidence.evaluate("movie", movie(3 to "2026-01-01"), day).state)
        assertEquals(TmdbAvailability.UPCOMING, TmdbReleaseEvidence.evaluate("movie", movie(4 to "2026-11-10"), day).state)
        assertEquals(TmdbAvailability.AVAILABLE, TmdbReleaseEvidence.evaluate("movie", movie(4 to "2026-11-09"), day).state)
        assertEquals(TmdbAvailability.AVAILABLE, TmdbReleaseEvidence.evaluate("movie", movie(6 to "2020-01-01"), day).state)
        assertEquals(TmdbAvailability.UNKNOWN, TmdbReleaseEvidence.evaluate("movie", JSONObject().put("release_date","2020-01-01"), day).state)
        assertEquals(TmdbAvailability.UNKNOWN, TmdbReleaseEvidence.evaluate("movie", movie(4 to "invalid"), day).state)
        assertEquals(TmdbAvailability.UNKNOWN, TmdbReleaseEvidence.evaluate("movie", movie(5 to "2020-01-01"), day).state)
    }
    @Test fun tvNeedsActualEpisodeAndStatusNotOnlyAFirstAirDate() {
        val detail = JSONObject().put("first_air_date","2026-01-01").put("status","Returning Series")
        assertEquals(TmdbAvailability.UNKNOWN, TmdbReleaseEvidence.evaluate("tv",detail,day).state)
        detail.put("number_of_episodes",12).put("last_air_date","2026-02-01")
        assertEquals(TmdbAvailability.AVAILABLE, TmdbReleaseEvidence.evaluate("tv",detail,day).state)
        detail.put("first_air_date","2027-01-01")
        assertEquals(TmdbAvailability.UPCOMING, TmdbReleaseEvidence.evaluate("tv",detail,day).state)
    }
    @Test fun animeUsesReviewedIdAndCollectionMembershipAndRetainsMovieIdentity(): Unit = runBlocking {
        assertEquals("电影",TmdbAnimeIp.category("movie",129,setOf(16),null,emptyMap()))
        assertEquals("电影",TmdbAnimeIp.category("movie",980477,setOf(16),null,emptyMap()))
        assertEquals("电影",TmdbAnimeIp.category("movie",999,setOf(16),null,emptyMap()))
        assertEquals("动漫",TmdbAnimeIp.category("movie",635302,setOf(16),null,emptyMap()))
        assertEquals("动漫",TmdbAnimeIp.category("movie",317442,setOf(16),null,emptyMap()))
        assertEquals("动漫",TmdbAnimeIp.category("movie",1017204,setOf(16),null,emptyMap()))
        assertEquals("动漫",TmdbAnimeIp.category("movie",999,setOf(16),7,mapOf(7 to 85937)))
        assertEquals("电影",TmdbAnimeIp.category("movie",999,setOf(28),7,mapOf(7 to 85937)))
        val fixture=TmdbBrowseFixture()
        val repo=TmdbBrowseRepository(fixture)
        val batch=repo.load(TmdbRailSpec(TmdbBrowsePage.ANIME,TmdbRailKind.HOT),1,"fixture")
        val movie=batch.entries.first { it.film.id=="tmdb-movie-635302" }
        assertEquals("movie",movie.type)
        assertNull(movie.film.tmdbTvId)
        assertEquals("movie" to 635302,HerrinTmdbDiscovery().identity(movie.film))
    }
    private fun domesticEntry(id: Int,countries:Set<String>) = TmdbBrowseEntry(
        Film("tmdb-movie-$id","Original","","电影","2026","",1),"movie",countries,
        TmdbReleaseProof(TmdbAvailability.AVAILABLE,day),1000,1.0,JSONObject(),JSONObject())
    @Test fun domesticBlocksAreEightTwoTwoAndCoproductionsUseOnlyOneSlot() {
        val rows=(1..30).map { domesticEntry(it,setOf("CN")) }+(31..40).map { domesticEntry(it,setOf("HK")) }+
            (41..50).map { domesticEntry(it,setOf("TW")) }+domesticEntry(1,setOf("CN","HK","TW"))
        val result=TmdbDomesticAllocator.allocate(rows,24)
        assertEquals(24,result.size);assertEquals(24,result.map{it.film.id}.distinct().size)
        assertEquals("tmdb-movie-1",result.first().film.id)
        result.chunked(12).forEach { block ->
            assertEquals(8,block.count{"CN" in it.countries})
            assertEquals(2,block.count{"HK" in it.countries})
            assertEquals(2,block.count{"TW" in it.countries})
        }
        val spill=TmdbDomesticAllocator.allocate(rows.filter{"HK" !in it.countries && "TW" !in it.countries},12)
        assertEquals(12,spill.size)
    }
    @Test fun fiveCategoriesHaveFourIndependentSpecsAndOfficialEndpoints() {
        for(page in TmdbBrowsePage.entries.filter{it!=TmdbBrowsePage.HOME}) {
            val specs=TmdbRailSpec.forPage(page)
            assertEquals(4,specs.size);assertEquals(4,specs.map{it.key}.distinct().size)
            assertEquals(listOf("热门"+page.label,"国内热门","最新上线","高分"+page.label),specs.map{it.title})
            for(spec in specs) for(type in spec.types) {
                val urls=spec.endpoints(type,day,if(spec.kind==TmdbRailKind.DOMESTIC)"HK" else null)
                assertTrue(urls.all{it.startsWith("/trending/")||it.startsWith("/discover/")})
                if(spec.kind==TmdbRailKind.LATEST&&type=="movie")assertTrue(urls.single().contains("with_release_type=4%7C6"))
                if(spec.kind==TmdbRailKind.RATED)assertTrue(urls.single().contains("vote_count.gte="))
            }
        }
        assertEquals(listOf("热门推荐","国内热门"),TmdbRailSpec.forPage(TmdbBrowsePage.HOME).map{it.title})
    }
    @Test fun everyRailFetchesCachesAndPaginatesWithoutPrivateSourceCalls(): Unit = runBlocking {
        val fixture=TmdbBrowseFixture();val directory=Files.createTempDirectory("tmdb-browse").toFile()
        try {
            val repo=TmdbBrowseRepository(fixture,directory)
            for(page in TmdbBrowsePage.entries)for(spec in TmdbRailSpec.forPage(page)) {
                val first=repo.load(spec,1,"fixture")
                assertTrue("Empty "+spec.key,first.entries.isNotEmpty())
                assertTrue(first.entries.all{it.availability.state==TmdbAvailability.AVAILABLE})
                assertTrue(first.entries.all{page==TmdbBrowsePage.HOME||it.film.category==page.label})
                assertEquals(first.entries.map{it.film.id},repo.cached(spec,1,"fixture")!!.entries.map{it.film.id})
                val next=repo.load(spec,2,"fixture");assertEquals(2,next.page)
            }
            assertTrue(fixture.requests.all{it.startsWith(TmdbEndpoints.PRIMARY)})
            fixture.offline=true
            val offline=TmdbBrowseRepository(fixture,directory)
            assertTrue(offline.cached(TmdbRailSpec(TmdbBrowsePage.HOME,TmdbRailKind.HOT),1,"fixture")!!.entries.isNotEmpty())
            assertNull(offline.cached(TmdbRailSpec(TmdbBrowsePage.HOME,TmdbRailKind.HOT),1,"other-account"))
            assertThrows(java.io.IOException::class.java) { runBlocking {
                offline.load(TmdbRailSpec(TmdbBrowsePage.HOME,TmdbRailKind.HOT),1,"fixture",true)
            } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun stateRetainsFocusedOrderAndCacheOnFailureAndCanRetry(): Unit = runBlocking {
        val fixture=TmdbBrowseFixture();val repo=TmdbBrowseRepository(fixture)
        val state=TmdbBrowseState(repo,"fixture");val spec=TmdbRailSpec(TmdbBrowsePage.MOVIE,TmdbRailKind.HOT)
        state.refresh(TmdbBrowsePage.MOVIE)
        val first=state.row(spec).entries.map{it.film.id}
        assertTrue(first.isNotEmpty());state.focus(spec.key)
        fixture.variant=1
        state.refresh(TmdbBrowsePage.MOVIE,true)
        assertEquals(first,state.row(spec).entries.map{it.film.id})
        state.focus(null)
        val updated=state.row(spec).entries.map{it.film.id}
        assertNotEquals(first,updated)
        fixture.offline=true;state.refresh(TmdbBrowsePage.MOVIE,true)
        assertTrue(state.row(spec).error);assertEquals(updated,state.row(spec).entries.map{it.film.id})
        fixture.offline=false;state.retry(spec);assertFalse(state.row(spec).error)
        state.more(spec);assertEquals(2,state.row(spec).page)
        assertEquals(state.row(spec).entries.size,state.row(spec).entries.map{it.film.id}.distinct().size)
    }
    @Test fun networkConcurrencyIsBoundedAndRunsOffCallerThread(): Unit = runBlocking {
        val original=Thread.currentThread();val active=AtomicInteger();val maximum=AtomicInteger()
        val fixture=TmdbBrowseFixture()
        val repo=TmdbBrowseRepository(ArtworkJsonFetcher{url,token->
            assertNotSame(original,Thread.currentThread())
            val n=active.incrementAndGet();maximum.updateAndGet{maxOf(it,n)}
            try {Thread.sleep(2);fixture.load(url,token)}finally{active.decrementAndGet()}
        })
        TmdbBrowseState(repo,"fixture").refresh(TmdbBrowsePage.HOME)
        assertTrue(maximum.get() in 1..3)
    }

    @Test fun backgroundVerifiedCollectionReclassifiesAndPersistsUnknownMovieWithoutChangingItsType(): Unit = runBlocking {
        val directory=Files.createTempDirectory("anime-relations").toFile()
        val fixture=TmdbBrowseFixture()
        val fetcher=ArtworkJsonFetcher { url,token ->
            val path=java.net.URI(url).path.removePrefix("/3")
            if (path.matches(Regex("/(movie|tv)/[0-9]+"))) {
                if(path=="/movie/999") JSONObject(fixture.load(url.replace("/movie/999","/movie/635302"),token)).put("id",999).toString()
                else fixture.load(url,token)
            } else JSONObject().put("page",1).put("total_pages",1).put("results",JSONArray().apply{
                if(!path.contains("/tv"))put(JSONObject().put("id",999).put("title","Original unknown")
                    .put("genre_ids",JSONArray().put(16)).put("vote_count",1000))
            }).toString()
        }
        try {
            val repo=TmdbBrowseRepository(fetcher,directory)
            val spec=TmdbRailSpec(TmdbBrowsePage.ANIME,TmdbRailKind.HOT)
            assertTrue(repo.load(spec,1,"fixture").entries.isEmpty())
            repo.warmAnimeRelations("fixture")
            val cached=repo.cached(spec,1,"fixture")!!.entries.single()
            assertEquals("动漫",cached.film.category);assertEquals("movie",cached.type);assertNull(cached.film.tmdbTvId)
            val offline=TmdbBrowseRepository(ArtworkJsonFetcher{_,_->error("Cache must not require network")},directory)
            assertEquals("tmdb-movie-999",offline.cached(spec,1,"fixture")!!.entries.single().film.id)
        }finally{directory.deleteRecursively()}
    }
    @Test fun imageSizesAndUnknownStatusRemainExplicit() {
        val film=HerrinTmdbDiscovery().parsePage("""{"results":[{"id":129,"title":"Original","genre_ids":[16],"poster_path":"/poster.jpg","backdrop_path":"/hero.jpg"}]}""","movie").single()
        assertTrue(film.artworkUrl!!.contains("/w500/"))
        assertTrue(film.backdropUrl!!.contains("/original/"))
        assertEquals("电影",film.category)
        assertEquals(TmdbAvailability.UNKNOWN,TmdbReleaseEvidence.evaluate("movie",JSONObject(),day).state)
    }

    @Test fun coProductionMatchingDoesNotConsumeTheOnlyHongKongSlotsForTheMainlandQuota() {
        val rows=(1..8).map{domesticEntry(it,setOf("CN","HK"))}+
            (9..16).map{domesticEntry(it,setOf("CN"))}+(17..18).map{domesticEntry(it,setOf("TW"))}
        val result=TmdbDomesticAllocator.allocate(rows,12)
        assertEquals(12,result.size);assertEquals(12,result.map{it.film.id}.distinct().size)
        assertEquals(8,result.count{it.domesticRegion=="CN"})
        assertEquals(2,result.count{it.domesticRegion=="HK"})
        assertEquals(2,result.count{it.domesticRegion=="TW"})
    }

    @Test fun searchUsesCachedClassificationOffTheCallerThread(): Unit = runBlocking {
        val caller=Thread.currentThread()
        val discovery=HerrinTmdbDiscovery(ArtworkJsonFetcher {_,_->
            """{"page":1,"total_pages":1,"results":[{"media_type":"movie","id":129,"title":"Original","genre_ids":[16]}]}"""
        }, browseClassification={type,id,genres->
            assertNotSame(caller,Thread.currentThread())
            TmdbAnimeIp.category(type,id,genres,null,emptyMap())
        })
        assertEquals("电影",discovery.search("Original","fixture").films.single().category)
    }
}
