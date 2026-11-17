package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Synthetic metadata exercises policy only; no assertion about actual provider playback. */
class LatestReleasePolicyTest {
    private val today = LocalDate.of(2026, 11, 17)
    private fun releases(vararg rows: Triple<String, Int, String>) = JSONObject().put("release_dates",
        JSONObject().put("results", JSONArray().apply { rows.groupBy { it.first }.forEach { (country, dates) ->
            put(JSONObject().put("iso_3166_1", country).put("release_dates", JSONArray().apply {
                dates.forEach { put(JSONObject().put("type", it.second).put("release_date", it.third)) }
            }))
        } }))
    @Test fun movieReleaseMatrixAndDigitalPriority() {
        fun state(type: Int, date: String) = TmdbReleaseEvidence.evaluate("movie", releases(Triple("US",type,date)),today).state
        for(type in 1..3) assertEquals(TmdbAvailability.THEATRICAL_ONLY,state(type,"2026-01-01"))
        for(type in 4..6) assertEquals(TmdbAvailability.AVAILABLE,state(type,"2026-11-17"))
        assertEquals(TmdbAvailability.UPCOMING,state(4,"2026-11-18T00:00:00Z"))
        assertEquals(TmdbAvailability.UNKNOWN,state(4,""))
        assertEquals(TmdbAvailability.UNKNOWN,TmdbReleaseEvidence.evaluate("person",JSONObject(),today).state)
        val dates = releases(Triple("US",3,"2026-01-01"),Triple("US",4,"2026-11-16"),Triple("US",5,"2026-11-17"))
        assertEquals(LocalDate.of(2026,11,16),TmdbReleaseEvidence.evaluate("movie",dates,today).date)
    }
    @Test fun usReleaseQualifiesBeforeChinaAndCountriesArePreserved() {
        val dates = releases(Triple("US",4,"2026-11-16"),Triple("CN",4,"2026-12-01"),Triple("JP",4,"2026-01-01"))
        val markets = TmdbLatestPolicy.releaseMarkets(TmdbBrowsePage.MOVIE)
        assertEquals(LocalDate.of(2026,11,16),TmdbReleaseEvidence.evaluate("movie",dates,today,markets).date)
        assertEquals(TmdbAvailability.UPCOMING,TmdbReleaseEvidence.evaluate("movie",dates,today,setOf("CN")).state)
        assertEquals(LocalDate.of(2026,1,1),TmdbReleaseEvidence.evaluate("movie",dates,today).date)
    }
    @Test fun airedEpisodeAndFutureNextEpisode() {
        val show = JSONObject().put("first_air_date","2026-11-01").put("last_air_date","2026-11-15")
            .put("status","Returning Series").put("number_of_episodes",8)
            .put("last_episode_to_air",JSONObject().put("air_date","2026-11-15"))
            .put("next_episode_to_air",JSONObject().put("air_date","2026-11-22"))
        assertEquals(LocalDate.of(2026,11,15),TmdbReleaseEvidence.evaluate("tv",show,today).date)
        for(status in listOf("Returning Series","Ended","Canceled","In Production")) {
            show.put("status",status)
            assertEquals(TmdbAvailability.AVAILABLE,TmdbReleaseEvidence.evaluate("tv",show,today).state)
        }
        show.put("first_air_date","2026-11-18")
        assertEquals(TmdbAvailability.UPCOMING,TmdbReleaseEvidence.evaluate("tv",show,today).state)
    }
    private fun entry(id:Int, country:String) = TmdbBrowseEntry(
        Film("tmdb-movie-$id","Original","","电影","2026","",1),"movie",setOf(country),
        TmdbReleaseProof(TmdbAvailability.AVAILABLE,today.minusDays(id.toLong())),1000,1.0,JSONObject(),JSONObject())
    @Test fun latestAnimeIncludesReleasedMoviesAndAiredSeriesWithoutChangingMediaIdentity() = kotlinx.coroutines.runBlocking {
        val fixture = TmdbBrowseFixture()
        val repository = TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            val root = JSONObject(fixture.load(url, token))
            if (url.contains("/discover/movie")) {
                val rows = root.getJSONArray("results")
                root.put("results", JSONArray().apply { for (j in 0 until minOf(2, rows.length())) put(rows.getJSONObject(j)) })
            }
            if (Regex("/movie/[0-9]+/release_dates").containsMatchIn(url)) {
                root.getJSONArray("results").let { regions -> for (j in 0 until regions.length()) {
                    val region = regions.getJSONObject(j).put("iso_3166_1", "JP")
                    val dates = region.getJSONArray("release_dates")
                    for (k in 0 until dates.length()) dates.getJSONObject(k).put("release_date", "2026-11-16")
                } }
            }
            root.toString()
        })
        val batch = repository.load(TmdbRailSpec(TmdbBrowsePage.ANIME, TmdbRailKind.LATEST), 1, "fixture")
        assertTrue(batch.entries.any { it.type == "movie" })
        assertTrue(batch.entries.any { it.type == "tv" })
        assertTrue(batch.entries.all { it.availability.state == TmdbAvailability.AVAILABLE })
        batch.entries.filter { it.type == "movie" }.forEach { assertNull(it.film.tmdbTvId) }
    }

    @Test fun regionBalanceSpillDedupAndDateOrdering() {
        val all=(1..20).map{entry(it,"US")}+(21..40).map{entry(it,"CN")}
        val balanced=TmdbLatestPolicy.allocate(TmdbBrowsePage.MOVIE,all+all)
        assertEquals(20,balanced.size)
        assertEquals(10,balanced.count{"US" in it.countries})
        assertEquals(10,balanced.count{"CN" in it.countries})
        assertEquals(balanced.sortedByDescending{it.availability.date},balanced)
        val spill=TmdbLatestPolicy.allocate(TmdbBrowsePage.MOVIE,all.take(20)+entry(30,"MO"))
        assertEquals(20,spill.size);assertEquals(1,spill.count{"MO" in it.countries})
        assertEquals(setOf("JP"),TmdbLatestPolicy.pools(TmdbBrowsePage.ANIME).first())
        assertEquals(setOf("KR"),TmdbLatestPolicy.pools(TmdbBrowsePage.VARIETY).last())
        assertEquals(setOf("CN","HK","MO","TW"),TmdbLatestPolicy.chinese)
        assertEquals(listOf(emptySet<String>()),TmdbLatestPolicy.pools(TmdbBrowsePage.DOCUMENTARY))
    }
}
