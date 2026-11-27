package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject

/** Index metadata only. File IDs remain the playback authority for every version/episode. */
internal object PersonalMediaProjection {
    fun metadata(film: Film, type: String) = JSONObject().put("identityType", type)
        .put("backdrop", film.backdropUrl).put("genres", JSONArray(film.genres))
        .put("people", JSONArray().apply { film.people.forEach { person ->
            put(JSONObject().put("name", person.name).put("role", person.role).put("portrait", person.portraitUrl))
        } }).put("actors", JSONArray(film.actors)).put("directors", JSONArray(film.directors))
        .put("rating", film.ratingValue).put("release", film.releaseDate).put("runtime", film.runtimeMinutes)

    private fun groupKey(row: JSONObject): String {
        val identityType = runCatching { JSONObject(row.optString("metadata")).optString("identityType") }
            .getOrNull()?.takeIf { it in setOf("movie", "tv") } ?: row.optString("type")
        if (identityType == "tv" && row.optInt("episode") <= 0 && row.optInt("season", -1) >= 0)
            return row.getString("id")
        return if (row.optInt("tmdb") > 0) "$identityType:${row.optString("type")}:${row.optInt("tmdb")}:S${row.optInt("season")}"
        else row.getString("id")
    }

    fun films(rows: List<JSONObject>): List<Film> = rows.groupBy(::groupKey).values.map { versions ->
        val row = versions.first()
        val meta = runCatching { JSONObject(row.optString("metadata")) }.getOrDefault(JSONObject())
        fun strings(key: String) = meta.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        val people = meta.optJSONArray("people")?.let { entries ->
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.map {
                FilmPerson(it.optString("name"), it.optString("role"), it.optString("portrait").takeIf(String::isNotBlank))
            }
        }.orEmpty()
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
            ratingProvider = "TMDB".takeIf { row.optInt("tmdb") > 0 },
            metadataSource = "TMDB".takeIf { row.optInt("tmdb") > 0 },
            releaseDate = meta.optString("release").takeIf(String::isNotBlank),
            runtimeMinutes = meta.optInt("runtime").takeIf { it > 0 },
            people = people,
            tmdbTvId = row.optInt("tmdb").takeIf {
                it > 0 && meta.optString("identityType", row.optString("type")) == "tv"
            })
    }
    /** Bounded input pages: retain one display record per work and actual playback lines,
     * rather than every file's duplicated cast/metadata JSON until the entire walk ends. */
    class Accumulator {
        private class Group(val base: Film, val season: Int?) {
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
                groups.getOrPut(key) {
                    Group(film.copy(playbackEpisodes = emptyList()), rows.first().optInt("season", -1).takeIf { it in 0..99 && rows.first().optInt("episode") > 0 })
                }.add(film)
            }
        }
        fun films(): List<Film> = groups.values.map(Group::film)

        fun browseFilms(): List<Film> {
            val seasons = groups.values.filter { it.season != null && it.base.tmdbTvId != null }
                .groupBy { it.base.tmdbTvId }
            val emitted = mutableSetOf<Int>()
            return groups.values.mapNotNull { group ->
                val series = group.base.tmdbTvId ?: return@mapNotNull group.film()
                if (group.season == null) return@mapNotNull group.film()
                val parts = seasons[series].orEmpty().sortedBy { it.season }
                if (parts.map { it.season }.distinct().size < 2) return@mapNotNull group.film()
                // Different category labels from a partial metadata refresh
                // can describe the same series and season. Merge only by TMDB ID.
                if (!emitted.add(series)) return@mapNotNull null
                val episodes = buildList {
                    parts.groupBy { requireNotNull(it.season) }.forEach { (season, rows) ->
                        val numbered = rows.flatMap { it.film().playbackEpisodes }.groupBy { it.number }.toSortedMap()
                        numbered.forEach { (number, versions) ->
                            val coordinate = if (season == 1) number else (season + 1) * 1_000 + number
                            add(PlaybackEpisode(coordinate, "S${season}E$number",
                                versions.flatMap { it.lines }.distinctBy { it.id }))
                        }
                    }
                }
                parts.first().base.copy(episodes = episodes.size, playbackEpisodes = episodes)
            }
        }
    }
}
