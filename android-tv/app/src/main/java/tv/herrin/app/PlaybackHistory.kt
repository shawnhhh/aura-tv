package tv.herrin.app

import android.content.Context
import java.security.MessageDigest

data class WatchPosition(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long) {
    val fraction: Float get() = if (durationMs <= 0L) 0f
        else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** Stores only playback offsets. Never stores stream URLs, access tokens or provider credentials. */
class PlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences("herrin-watch-history", Context.MODE_PRIVATE)

    private fun key(scope: String, filmId: String, episode: Int, lineId: String): String {
        val plain = "${scope.length}:${scope}|${filmId.length}:${filmId}|${episode}|${lineId.length}:${lineId}"
        return MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

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
        if (scope.isBlank() || filmId.isBlank() || lineId.isBlank() || episode !in 1..99) return
        val token = key(scope, filmId, episode, lineId)
        val completed = ended || durationMs > 0L && positionMs >= durationMs - 3_000L
        if (completed) { clear(scope, filmId, episode, lineId); return }
        if (durationMs < 10_000L || durationMs > 48L * 60L * 60_000L ||
            positionMs < 3_000L || positionMs >= durationMs) return
        prefs.edit()
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
