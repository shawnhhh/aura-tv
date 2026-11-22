package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject

/** Index metadata only. File IDs remain the playback authority for every version/episode. */
internal object PersonalMediaProjection {
    fun metadata(film: Film, type: String) = JSONObject().put("identityType", type)
        .put("backdrop", film.backdropUrl).put("genres", JSONArray(film.genres))
        .put("actors", JSONArray(film.actors)).put("directors", JSONArray(film.directors))
        .put("rating", film.ratingValue).put("release", film.releaseDate).put("runtime", film.runtimeMinutes)

    private fun groupKey(row: JSONObject): String {
        val identityType = runCatching { JSONObject(row.optString("metadata")).optString("identityType") }
            .getOrNull()?.takeIf { it in setOf("movie", "tv") } ?: row.optString("type")
        return if (row.optInt("tmdb") > 0) "$identityType:${row.optString("type")}:${row.optInt("tmdb")}:S${row.optInt("season")}"
        else row.getString("id")
    }

    fun films(rows: List<JSONObject>): List<Film> = rows.groupBy(::groupKey).values.map { versions ->
        val row = versions.first()
        val meta = runCatching { JSONObject(row.optString("metadata")) }.getOrDefault(JSONObject())
        fun strings(key: String) = meta.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        val episodes = versions.groupBy { it.optInt("episode").takeIf { e -> e > 0 } ?: 1 }.toSortedMap().map { (number, files) ->
            PlaybackEpisode(number, if (files.first().optInt("episode") > 0) "第${number}集" else "正片",
                files.map { f -> PlaybackLine("personal-file:" + f.getString("id"), f.getString("name"), "personal-index:" + f.getString("id")) })
        }
        Film("personal-index:" + row.getString("id"), row.optString("title").ifBlank { row.getString("name") },
            row.optString("name"), when(row.optString("type")) {
                "tv" -> "剧集"; "anime" -> "动漫"; "documentary" -> "纪录片"; "video" -> "其他"; else -> "电影"
            }, row.optString("year"), row.optString("synopsis"), R.drawable.photofilm_orbit,
            episodes = episodes.size, playbackEpisodes = episodes,
            artworkUrl = row.optString("poster").takeIf(String::isNotBlank), backdropUrl = meta.optString("backdrop").takeIf(String::isNotBlank),
            genres = strings("genres"), actors = strings("actors"), directors = strings("directors"),
            ratingValue = meta.optDouble("rating").takeIf { it.isFinite() && it > 0 },
            ratingProvider = "TMDB".takeIf { row.optInt("tmdb") > 0 }, metadataSource = "TMDB".takeIf { row.optInt("tmdb") > 0 })
    }
    /** Bounded input pages: retain one display record per work and actual playback lines,
     * rather than every file's duplicated cast/metadata JSON until the entire walk ends. */
    class Accumulator {
        private class Group(val base: Film) {
            val episodes = sortedMapOf<Int, Pair<String, MutableList<PlaybackLine>>>()
            fun add(film: Film) {
                for (episode in film.playbackEpisodes) {
                    episodes.getOrPut(episode.number) { episode.title to mutableListOf() }.second.addAll(episode.lines)
                }
            }
            fun film(): Film = base.copy(episodes = episodes.size, playbackEpisodes = episodes.map { (number, entry) ->
                PlaybackEpisode(number, entry.first, entry.second.toList())
            })
        }
        private val groups = linkedMapOf<String, Group>()
        fun add(page: List<JSONObject>) {
            for ((key, rows) in page.groupBy(::groupKey)) {
                val film = PersonalMediaProjection.films(rows).single()
                groups.getOrPut(key) { Group(film.copy(playbackEpisodes = emptyList())) }.add(film)
            }
        }
        fun films(): List<Film> = groups.values.map(Group::film)
    }
}
