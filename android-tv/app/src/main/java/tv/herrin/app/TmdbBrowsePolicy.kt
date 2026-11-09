package tv.herrin.app

import org.json.JSONObject
import java.time.LocalDate

/** A browse category never changes the original TMDB movie/tv playback identity. */
internal enum class TmdbBrowsePage(val label: String) {
    HOME("首页"), MOVIE("电影"), SERIES("剧集"), ANIME("动漫"), VARIETY("综艺"), DOCUMENTARY("纪录片");
    companion object { fun from(label: String) = entries.firstOrNull { it.label == label } }
}
internal enum class TmdbRailKind { HOT, DOMESTIC, LATEST, RATED }
internal data class TmdbRailSpec(val page: TmdbBrowsePage, val kind: TmdbRailKind) {
    val key get() = page.name.lowercase() + "-" + kind.name.lowercase()
    val title get() = when (kind) {
        TmdbRailKind.HOT -> if (page == TmdbBrowsePage.HOME) "热门推荐" else "热门" + page.label
        TmdbRailKind.DOMESTIC -> "国内热门"
        TmdbRailKind.LATEST -> "最新上线"
        TmdbRailKind.RATED -> "高分" + page.label
    }
    val types get() = when (page) {
        TmdbBrowsePage.MOVIE -> listOf("movie")
        TmdbBrowsePage.SERIES, TmdbBrowsePage.VARIETY -> listOf("tv")
        else -> listOf("movie", "tv")
    }
    fun endpoints(type: String, today: LocalDate, region: String? = null): List<String> {
        val genre = when (page) {
            TmdbBrowsePage.ANIME -> "&with_genres=16"
            TmdbBrowsePage.VARIETY -> "&with_genres=10764%7C10767"
            TmdbBrowsePage.DOCUMENTARY -> "&with_genres=99"
            else -> ""
        }
        val country = region?.let { "&with_origin_country=$it" }.orEmpty()
        val discover = "/discover/$type?include_adult=false$genre$country"
        return when (kind) {
            TmdbRailKind.HOT -> listOf("/trending/$type/week", "$discover&sort_by=popularity.desc")
            TmdbRailKind.DOMESTIC -> listOf("$discover&sort_by=popularity.desc")
            TmdbRailKind.LATEST -> listOf(if (type == "movie")
                "$discover&with_release_type=4%7C6&release_date.lte=$today&sort_by=primary_release_date.desc"
                else "$discover&first_air_date.lte=$today&air_date.lte=$today&include_null_first_air_dates=false&sort_by=first_air_date.desc")
            TmdbRailKind.RATED -> listOf("$discover&vote_count.gte=" + minVotes(type) + "&sort_by=vote_average.desc")
        }
    }
    fun minVotes(type: String) = if (type == "movie") 300 else 100
    companion object {
        fun forPage(page: TmdbBrowsePage) = (if (page == TmdbBrowsePage.HOME)
            listOf(TmdbRailKind.HOT, TmdbRailKind.DOMESTIC) else TmdbRailKind.entries).map { TmdbRailSpec(page, it) }
    }
}

internal enum class TmdbAvailability { AVAILABLE, UPCOMING, THEATRICAL_ONLY, UNKNOWN }
internal data class TmdbReleaseProof(val state: TmdbAvailability, val date: LocalDate? = null)
internal object TmdbReleaseEvidence {
    private fun date(text: String) = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    fun evaluate(type: String, detail: JSONObject, today: LocalDate): TmdbReleaseProof {
        if (type == "tv") {
            val first = date(detail.optString("first_air_date")) ?: return TmdbReleaseProof(TmdbAvailability.UNKNOWN)
            if (first > today) return TmdbReleaseProof(TmdbAvailability.UPCOMING, first)
            val last = date(detail.optString("last_air_date"))
                ?: detail.optJSONObject("last_episode_to_air")?.let { date(it.optString("air_date")) }
            val actual = last != null && last >= first && last <= today && detail.optInt("number_of_episodes") > 0
            val status = detail.optString("status")
            return if (actual && status in setOf("Returning Series", "Ended", "Canceled", "In Production"))
                TmdbReleaseProof(TmdbAvailability.AVAILABLE, first) else TmdbReleaseProof(TmdbAvailability.UNKNOWN)
        }
        val dates = mutableListOf<LocalDate>()
        var theatre = false
        var future = false
        val regions = detail.optJSONObject("release_dates")?.optJSONArray("results")
        if (regions != null) for (i in 0 until minOf(regions.length(), 100)) {
            val rows = regions.optJSONObject(i)?.optJSONArray("release_dates") ?: continue
            for (j in 0 until minOf(rows.length(), 100)) {
                val row = rows.optJSONObject(j) ?: continue
                val day = date(row.optString("release_date")) ?: continue
                if (row.optInt("type") in setOf(4, 6)) {
                    if (day <= today) dates += day else future = true
                } else if (row.optInt("type") in 1..3 && day <= today) theatre = true
            }
        }
        return when {
            dates.isNotEmpty() -> TmdbReleaseProof(TmdbAvailability.AVAILABLE, dates.minOrNull())
            future -> TmdbReleaseProof(TmdbAvailability.UPCOMING)
            theatre -> TmdbReleaseProof(TmdbAvailability.THEATRICAL_ONLY)
            date(detail.optString("release_date"))?.let { it > today } == true -> TmdbReleaseProof(TmdbAvailability.UPCOMING)
            else -> TmdbReleaseProof(TmdbAvailability.UNKNOWN)
        }
    }
}

/** Corrections are canonical IDs, never words in a title or an interface language. */
internal object TmdbAnimeIp {
    // Canonical TMDB pages document these reviewed movie -> serial-TV associations.
    // https://www.themoviedb.org/movie/635302 -> /tv/85937 (Demon Slayer)
    // https://www.themoviedb.org/movie/900667 and /movie/568012 -> /tv/37854 (One Piece)
    val serialMovies = mapOf(635302 to 85937, 900667 to 37854, 568012 to 37854)
    val standaloneMovies = setOf(129, 980477) // Spirited Away; Ne Zha 2
    fun category(type: String, id: Int, genres: Set<Int>, collection: Int?, verifiedCollections: Map<Int, Int>): String {
        if (type == "movie" && id in standaloneMovies) return "电影"
        if (16 in genres && (type == "tv" || id in serialMovies ||
                (collection != null && verifiedCollections.containsKey(collection)))) return "动漫"
        if (99 in genres) return "纪录片"
        if (type == "tv" && genres.any { it in setOf(10764, 10767) }) return "综艺"
        return if (type == "movie") "电影" else "剧集"
    }
}

internal data class TmdbBrowseEntry(
    val film: Film, val type: String, val countries: Set<String>, val availability: TmdbReleaseProof,
    val votes: Int, val popularity: Double, val raw: JSONObject, val proof: JSONObject, val domesticRegion: String? = null
)
internal object TmdbDomesticAllocator {
    /** Repeated 8/2/2 blocks. A co-production can occupy only one slot; shortages spill to remaining pools. */
    fun allocate(entries: List<TmdbBrowseEntry>, limit: Int = 12): List<TmdbBrowseEntry> {
        val candidates = entries.filter { it.countries.any { country -> country in setOf("CN", "HK", "TW") } }.distinctBy { it.film.id }
        val rank = candidates.mapIndexed { index, entry -> entry.film.id to index }.toMap()
        val used = hashSetOf<String>()
        val result = mutableListOf<TmdbBrowseEntry>()
        val pattern = List(8) { "CN" } + List(2) { "HK" } + List(2) { "TW" }
        val count = limit.coerceIn(0, 200)
        while (result.size < count) {
            val slots = pattern.take(minOf(12, count - result.size))
            val assigned = arrayOfNulls<TmdbBrowseEntry>(slots.size)
            val owner = mutableMapOf<String, Int>()
            fun match(slot: Int, visited: MutableSet<String>): Boolean {
                for (entry in candidates) {
                    if (entry.film.id in used || slots[slot] !in entry.countries || !visited.add(entry.film.id)) continue
                    val previous = owner[entry.film.id]
                    if (previous == null || match(previous, visited)) {
                        owner[entry.film.id] = slot
                        assigned[slot] = entry
                        return true
                    }
                }
                return false
            }
            slots.indices.forEach { match(it, hashSetOf()) }
            val block = mutableListOf<TmdbBrowseEntry>()
            assigned.forEachIndexed { i, entry -> if (entry != null && used.add(entry.film.id))
                block += entry.copy(domesticRegion = slots[i]) }
            for (entry in candidates) if (block.size < slots.size && used.add(entry.film.id))
                block += entry.copy(domesticRegion = entry.countries.first { it in setOf("CN", "HK", "TW") })
            if (block.isEmpty()) break
            result += block.sortedWith(compareBy<TmdbBrowseEntry> { slots.indexOf(it.domesticRegion) }.thenBy { rank.getValue(it.film.id) })
        }
        return result
    }
}
