package tv.herrin.app

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** TMDB is a display-only source; episode mapping is accepted only from numbered TV data. */
internal data class TmdbEpisodeDisplay(val number: Int, val stillUrl: String?,
                                       val runtimeMinutes: Int?, val officialTitle: String? = null)

internal object TmdbSeasonParser {
    private val stillPath = Regex("^/[A-Za-z0-9_./-]+\\.(?:jpg|jpeg|png|webp)$")

    fun parse(raw: String, expectedSeason: Int): Map<Int, TmdbEpisodeDisplay> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        if (root.optInt("season_number", -1) != expectedSeason) return emptyMap()
        val episodes = root.optJSONArray("episodes") ?: return emptyMap()
        val accepted = linkedMapOf<Int, TmdbEpisodeDisplay>()
        for (i in 0 until minOf(episodes.length(), 100)) {
            val entry = episodes.optJSONObject(i) ?: continue
            if (entry.optInt("season_number", expectedSeason) != expectedSeason) continue
            val number = entry.optInt("episode_number", 0).takeIf { it in 1..999 } ?: continue
            // Duplicate official episode IDs are not trustworthy mappings.
            if (number in accepted) return emptyMap()
            val path = entry.optString("still_path").takeIf {
                stillPath.matches(it) && ".." !in it && "//" !in it && "/./" !in it
            }
            val runtime = entry.optInt("runtime", 0).takeIf { it in 1..360 }
            accepted[number] = TmdbEpisodeDisplay(number,
                path?.let { "https://image.tmdb.org/t/p/w780$it" }, runtime,
                entry.optString("name").trim().take(100).takeIf { it.isNotEmpty() })
        }
        return accepted
    }
}

/** Short-lived, credential-partitioned metadata cache. Fetcher is injected for offline tests. */
internal class TmdbSeasonEpisodes(
    private val cacheDir: File,
    private val fetcher: ArtworkJsonFetcher
) {
    fun lookup(tvId: Int?, season: Int, token: String): Map<Int, TmdbEpisodeDisplay> {
        if (tvId == null || tvId <= 0 || season !in 1..99 ||
            !Regex("^[A-Za-z0-9._~-]{20,1000}$").matches(token)) return emptyMap()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$tvId|$season|$token".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "tmdb-season-$hash.json")
        if (file.isFile && file.length() in 1..60_000 &&
            System.currentTimeMillis() - file.lastModified() in 0L..(3L * 24 * 3600 * 1000)) {
            return TmdbSeasonParser.parse(file.readText(), season)
        }
        val response = runCatching {
            fetcher.load("https://api.themoviedb.org/3/tv/$tvId/season/$season?language=zh-CN", token)
        }.getOrNull() ?: return emptyMap()
        if (response.length > 60_000) return emptyMap()
        val parsed = TmdbSeasonParser.parse(response, season)
        if (parsed.isNotEmpty()) runCatching {
            cacheDir.mkdirs()
            cacheDir.listFiles()?.filter { it.name.startsWith("tmdb-season-") }
                ?.takeIf { it.size > 120 }?.sortedBy { it.lastModified() }
                ?.take(20)?.forEach { it.delete() }
            file.writeText(response)
        }
        return parsed
    }
}
