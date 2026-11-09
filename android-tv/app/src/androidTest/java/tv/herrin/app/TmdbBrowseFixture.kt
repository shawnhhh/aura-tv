package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Original metadata fixtures, never assertions of real third-party availability or media playback. */
internal class TmdbBrowseFixture : ArtworkJsonFetcher {
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    var offline = false
    var variant = 0
    private val identities = ConcurrentHashMap<Int, Pair<Set<Int>, String>>()
    private fun row(id: Int, type: String, genres: Set<Int>, country: String): JSONObject {
        identities[id] = genres to country
        return JSONObject().put("id", id).put("media_type", type).put("title", "Original $id").put("name", "Original $id")
            .put("genre_ids", JSONArray(genres.toList())).put("release_date", "2026-01-01").put("first_air_date", "2026-01-01")
            .put("vote_average", 8.0).put("vote_count", 1000).put("popularity", 1000 - id % 100)
    }
    override fun load(url: String, token: String): String {
        requests += url
        if (offline) throw java.io.IOException("fixture offline")
        val uri = URI(url)
        val path = uri.path.removePrefix("/3")
        val type = if (path.contains("/tv")) "tv" else "movie"
        val details = Regex("/(movie|tv)/([0-9]+)").matchEntire(path)
        if (details != null) {
            val id = details.groupValues[2].toInt()
            val (genres, country) = identities[id] ?: (setOf(16) to "JP")
            val d = JSONObject().put("id", id).put("production_countries", JSONArray().put(JSONObject().put("iso_3166_1", country)))
                .put("genres", JSONArray().apply { genres.forEach { put(JSONObject().put("id", it)) } })
            if (type == "tv") d.put("status", "Returning Series").put("first_air_date", "2026-01-01")
                .put("last_air_date", "2026-02-01").put("number_of_episodes", 12)
            else d.put("release_dates", JSONObject().put("results", JSONArray().put(JSONObject().put("release_dates",
                JSONArray().put(JSONObject().put("type", 4).put("release_date", "2026-01-01T00:00:00Z"))))))
            if (id in TmdbAnimeIp.serialMovies) d.put("belongs_to_collection", JSONObject().put("id", if(id==635302)700001 else 700002))
            return d.toString()
        }
        if (path.endsWith("configuration")) return "{\"images\":{}}"
        val query = uri.rawQuery.orEmpty()
        val page = Regex("(?:^|&)page=([0-9]+)").find(query)?.groupValues?.get(1)?.toInt() ?: 1
        val country = Regex("with_origin_country=(CN|HK|TW)").find(query)?.groupValues?.get(1) ?: "CN"
        val genre = when {
            query.contains("with_genres=16") -> setOf(16)
            query.contains("with_genres=99") -> setOf(99)
            query.contains("with_genres=10764") -> setOf(10764)
            else -> setOf(18)
        }
        val output = JSONArray()
        if (path == "/trending/all/week") {
            output.put(row(11 + variant, "movie", setOf(28), "CN")).put(row(21, "tv", setOf(18), "CN"))
        } else if (query.contains("with_origin_country")) {
            val base = mapOf("CN" to 100, "HK" to 200, "TW" to 300).getValue(country) +
                (if (type == "tv") 1000 else 0) + (if (16 in genre) 10000 else if (99 in genre) 20000 else if (10764 in genre) 30000 else 0)
            repeat(12) { output.put(row(base + (page - 1) * 20 + it, type, genre, country)) }
        } else if (path.contains("trending")) {
            if (type == "movie") {
                output.put(row(11 + variant, type, setOf(28), country)).put(row(51, type, setOf(99), country))
                    .put(row(635302, type, setOf(16), "JP"))
            } else {
                output.put(row(21, type, setOf(18), country)).put(row(31, type, setOf(16), "JP"))
                    .put(row(41, type, setOf(10764), country)).put(row(52, type, setOf(99), country))
            }
        } else {
            val id = if (type == "movie") { if (16 in genre) 635302 else if (99 in genre) 51 else 11 }
                else { if (16 in genre) 31 else if (99 in genre) 52 else if (10764 in genre) 41 else 21 }
            output.put(row(id + if (page > 1) page * 100000 else 0, type, genre, country))
        }
        return JSONObject().put("page", page).put("total_pages", 3).put("results", output).toString()
    }
}
