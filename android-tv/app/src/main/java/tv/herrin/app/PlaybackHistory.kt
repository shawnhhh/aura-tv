package tv.herrin.app

import android.content.Context
import java.security.MessageDigest

data class WatchPosition(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long) {
    val fraction: Float get() = if (durationMs <= 0L) 0f
        else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** Stores public display metadata and playback offsets. Never stores stream URLs, access tokens or provider credentials. */
class PlaybackHistory(private val context: Context) {
    private fun readIndex(): org.json.JSONArray = try { org.json.JSONArray(prefs.getString("recent-index", "[]")) }
        catch (_: org.json.JSONException) { org.json.JSONArray() }
    private val snapshots = TvBoxCatalogSnapshot(context, Long.MAX_VALUE)
    @Synchronized fun recordFilm(ticket: Long, scope: String, film: Film, episode: Int, line: String, position: Long, duration: Long) {
        if (generation != ticket || position < 3_000L || duration < 10_000L) return
        writeRecord(scope, film, episode, line, position, duration, System.currentTimeMillis())
    }
    private fun writeRecord(scope: String, film: Film, episode: Int, line: String, position: Long, duration: Long, at: Long) {
        val id = film.id
        val entries = readIndex()
        val coordinate = HerrinPlaybackCoordinate.from(film, episode, line)
        val scopeHash = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val next = org.json.JSONArray().put(org.json.JSONObject().put("id", id).put("scope", scopeHash)
            .put("key", key(scope, id, episode, line)).put("position", position).put("duration", duration)
            .put("season", coordinate?.season ?: 0).put("logicalEpisode", coordinate?.episode ?: 0)
            .put("portable", coordinate?.canMapAcrossSources == true)
            .put("episode", episode).put("line", line.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,150}")) } ?: "").put("at", at))
        for (i in 0 until entries.length()) if (entries.optJSONObject(i)?.optString("id") != id && next.length() < 100)
            next.put(entries.getJSONObject(i))
        val ordered = (0 until next.length()).map { next.getJSONObject(it) }.sortedByDescending { it.optLong("at") }
        prefs.edit().putString("recent-index", org.json.JSONArray(ordered).toString()).apply()
        try { snapshots.save("history-index", id, listOf(film)) } catch (_: java.io.IOException) {
            HerrinPlaybackDiagnosticJournal.record("RECOVERY", "HISTORY_INDEX_WRITE_FAILED")
        }
    }
    /** One-time projection of pre-index offsets, only once their authorized source playlist is available. */
    @Synchronized fun importLegacy(scope: String, films: List<Film>): Int {
        val rows = readIndex()
        val indexed = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }.toSet()
        var imported = 0
        for (film in films) {
            if (film.id in indexed) continue
            val latest = film.playbackEpisodes.flatMap { ep -> ep.lines.mapNotNull { line ->
                load(scope, film.id, ep.number, line.id)?.let { Triple(ep.number, line.id, it) }
            } }.maxByOrNull { it.third.updatedAtMs } ?: continue
            writeRecord(scope, film, latest.first, latest.second, latest.third.positionMs, latest.third.durationMs, latest.third.updatedAtMs)
            imported++
        }
        return imported
    }
    fun indexedFilms(): List<Film> = runCatching {
        val rows = readIndex()
        (0 until rows.length()).flatMap { index ->
            val row = rows.getJSONObject(index)
            snapshots.load("history-index", row.getString("id")).map { film ->
                val watched = prefs.getBoolean(row.optString("key") + ":watched", false)
                val percent = if (watched) 100 else (100.0 * row.optLong("position") / row.optLong("duration", 1).coerceAtLeast(1)).toInt().coerceIn(0,100)
                film.copy(subtitle = "第${row.optInt("logicalEpisode").takeIf { it > 0 } ?: row.optInt("episode")}集 · ${if (watched) "已看完" else "$percent%"}")
            }
        }
    }.getOrDefault(emptyList())
    fun indexedProgress(filmId: String): WatchPosition? {
        val rows = readIndex()
        val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("id") == filmId } ?: return null
        val duration = row.optLong("duration")
        if (duration <= 0) return null
        val completed = prefs.getBoolean(row.optString("key") + ":watched", false)
        return WatchPosition(if (completed) duration else row.optLong("position"), duration, row.optLong("at"))
    }

    @Synchronized fun resolveIndexed(film: Film, scope: String): Pair<PlaybackRequest, Long>? {
        val rows = readIndex()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == film.id } ?: return null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val choices = film.playbackEpisodes.flatMap { ep -> ep.lines.map { ep to it } }.filter { (ep, line) ->
            if (row.optBoolean("portable")) HerrinPlaybackCoordinate(row.optInt("season").takeIf { it > 0 }, row.optInt("logicalEpisode")).accepts(ep,line)
            else row.optString("scope") == hash && ep.number == row.optInt("episode") && line.id == row.optString("line")
        }
        val selected = choices.firstOrNull { it.second.id == row.optString("line") } ?: choices.firstOrNull() ?: return null
        val watched = prefs.getBoolean(row.optString("key") + ":watched", false)
        return PlaybackRequest(film.id, selected.first.number, selected.second.id) to if (watched) 0L else row.optLong("position")
    }
    private val activeWrites = mutableMapOf<String, Long>()
    private var generation = 0L
    private val prefs = context.getSharedPreferences("herrin-watch-history", Context.MODE_PRIVATE)

    private fun key(scope: String, filmId: String, episode: Int, lineId: String): String {
        val plain = "${scope.length}:${scope}|${filmId.length}:${filmId}|${episode}|${lineId.length}:${lineId}"
        return MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun begin(scope: String, film: String, episode: Int, line: String): Long {
        val token = key(scope, film, episode, line)
        val ticket = ++generation
        activeWrites.clear() // The single production player retires all outgoing writers.
        activeWrites[token] = ticket
        return ticket
    }
    @Synchronized fun updateSession(ticket: Long, scope: String, film: String, episode: Int,
        line: String, position: Long, duration: Long, ended: Boolean) {
        val token = key(scope, film, episode, line)
        if (activeWrites[token] != ticket) return
        update(scope, film, episode, line, position, duration, ended)
        if (ended) activeWrites.remove(token)
    }
    fun isWatched(scope: String, film: String, episode: Int, line: String): Boolean =
        prefs.getBoolean(key(scope, film, episode, line) + ":watched", false)

    fun load(scope: String, filmId: String, episode: Int, lineId: String): WatchPosition? {
        val token = key(scope, filmId, episode, lineId)
        val position = prefs.getLong("${token}:position", 0L)
        val duration = prefs.getLong("${token}:duration", 0L)
        val timestamp = prefs.getLong("${token}:updated", 0L)
        if (duration <= 0L || position <= 0L || position >= duration || timestamp <= 0L) return null
        return WatchPosition(position, duration, timestamp)
    }

    fun update(
        scope: String, filmId: String, episode: Int, lineId: String,
        positionMs: Long, durationMs: Long, ended: Boolean = false
    ) {
        // CMS / Spider adapters can expose up to 300 source positions; these are global
        // source indexes, *not* per-season episode numbers.
        if (scope.isBlank() || filmId.isBlank() || lineId.isBlank() || episode !in 1..300) return
        val token = key(scope, filmId, episode, lineId)
        val completed = ended || durationMs > 0L && positionMs >= durationMs - 3_000L
        if (completed) { clear(scope, filmId, episode, lineId); prefs.edit().putBoolean("${token}:watched", true).apply(); return }
        if (durationMs < 10_000L || durationMs > 48L * 60L * 60_000L ||
            positionMs < 3_000L || positionMs >= durationMs) return
        prefs.edit()
            .putBoolean("${token}:watched", false)
            .putLong("${token}:position", positionMs)
            .putLong("${token}:duration", durationMs)
            .putLong("${token}:updated", System.currentTimeMillis())
            .apply()
    }

    fun clear(scope: String, filmId: String, episode: Int, lineId: String) {
        val token = key(scope, filmId, episode, lineId)
        prefs.edit().remove("${token}:position").remove("${token}:duration")
            .remove("${token}:updated").apply()
    }

    /** Uses current catalog to avoid exposing stale watch history from other endpoints. */
    fun recent(scope: String, films: List<Film>): List<Film> = films.mapNotNull { film ->
        val latest = film.playbackEpisodes.asSequence().flatMap { episode ->
            episode.lines.asSequence().mapNotNull { line ->
                load(scope, film.id, episode.number, line.id)
            }
        }.maxByOrNull { it.updatedAtMs }
        if (latest == null) null else film to latest.updatedAtMs
    }.sortedByDescending { it.second }.map { it.first }
}
