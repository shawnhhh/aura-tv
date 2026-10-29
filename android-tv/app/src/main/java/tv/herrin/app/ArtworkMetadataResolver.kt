package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Licensed, opt-in artwork enrichment via TMDB's official API. HERRIN never includes an API
 * credential, nor does it scrape unlicensed gallery pages. Images remain TMDB-hosted.
 * See https://developer.themoviedb.org/docs/faq for API terms and attribution obligations.
 */
/** Display-only TMDB metadata. Playback IDs and stream URLs never enter this model. */
internal data class EnrichedArtwork(
    val posterUrl: String?, val backdropUrl: String?,
    val synopsis: String? = null, val year: String? = null,
    val rating: Double? = null, val genres: List<String> = emptyList(),
    val category: String? = null, val originalTitle: String? = null
)

internal object ArtworkTitle {
    private val suffixes = Regex(
        "(?i)(?:[\\[【(（][^\\]】)）]{0,30}(?:国语|国配|中字|双语|字幕|超清|高清|蓝光|WEB[- ]?DL|BD|HD|4K|1080P|720P|完整版|完结|更新|第\\d+集|202[0-9])[^\\]】)）]{0,30}[\\]】)）])"
    )
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")

    fun searchQuery(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        .replace(suffixes, "")
        .replace(Regex("[_.·]+"), " ")
        .trim()

    fun normalize(raw: String): String {
        return searchQuery(raw)
            .replace(punctuation, "")
            .lowercase(Locale.ROOT)
    }
}

internal data class ArtworkSearchCandidate(
    val names: List<String>, val year: Int?, val posterPath: String?, val backdropPath: String?,
    val synopsis: String = "", val rating: Double? = null,
    val voteCount: Int = 0, val genreIds: List<Int> = emptyList(),
    val originalTitle: String? = null
)

internal object ArtworkMatch {
    /** Exact canonical title only. Ambiguous sequels/remakes must fall back to source artwork. */
    fun best(title: String, year: String, options: List<ArtworkSearchCandidate>): ArtworkSearchCandidate? {
        val target = ArtworkTitle.normalize(title)
        if (target.length < 2) return null
        val matching = options.filter { candidate ->
            candidate.names.any { ArtworkTitle.normalize(it) == target } &&
                (candidate.posterPath != null || candidate.backdropPath != null)
        }
        if (matching.isEmpty()) return null
        val preferredYear = Regex("(?:19|20)\\d{2}").find(year)?.value?.toIntOrNull()
        if (preferredYear != null) {
            val yearMatches = matching.filter { it.year != null && kotlin.math.abs(it.year - preferredYear) <= 1 }
            if (yearMatches.size == 1) return yearMatches.single()
            if (yearMatches.size > 1) {
                val bestDistance = yearMatches.minOf { kotlin.math.abs(it.year!! - preferredYear) }
                return yearMatches.singleOrNull { kotlin.math.abs(it.year!! - preferredYear) == bestDistance }
            }
            // A different known release year is a strong signal that we matched the wrong work.
            if (matching.any { it.year != null }) return null
        }
        return matching.singleOrNull()
    }
}

internal fun interface ArtworkJsonFetcher {
    fun load(url: String, readAccessToken: String): String?
}

/** Keyed by title, category, year and a one-way token fingerprint to avoid stale cross-account metadata. */
internal class ArtworkMetadataResolver(
    private val cacheRoot: File,
    private val fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher
) {
    fun lookup(film: Film, readAccessToken: String): EnrichedArtwork? {
        val token = readAccessToken.trim()
        if (!Regex("^[A-Za-z0-9._~-]{20,1000}$").matches(token) || film.title.isBlank()) return null
        val queryTitle = ArtworkTitle.searchQuery(film.title)
        if (queryTitle.isBlank()) return null
        val category = when (film.category) {
            "电影", "纪录片" -> "movie"
            "剧集", "电视剧", "动漫", "动画", "综艺" -> "tv"
            else -> return null
        }
        val key = hash("$category|$queryTitle|${film.year}|${hash(token)}")
        val file = File(cacheRoot, "$key.json")
        if (file.isFile && file.length() in 1..8192) {
            val parsed = runCatching { JSONObject(file.readText()) }.getOrNull()
            val maxAge = if (parsed?.optBoolean("negative") == true) 3L * 3600 * 1000
                else 14L * 24 * 3600 * 1000
            if (System.currentTimeMillis() - file.lastModified() in 0L..maxAge) {
                if (parsed?.optBoolean("negative") == true) return null
                parsed?.let { decodeCached(it) }?.let { return it }
            }
        }
        val tokenKey = hash(token)
        if ((rejectedTokens[tokenKey] ?: 0L) > System.currentTimeMillis()) return null
        // Multiple 80-title shelves must not flood TMDB with simultaneous requests.
        networkLimit.acquire()
        try {
            if ((rejectedTokens[tokenKey] ?: 0L) > System.currentTimeMillis()) return null
            val searchUrl = "https://api.themoviedb.org/3/search/$category?query=" +
                URLEncoder.encode(queryTitle, "UTF-8") + "&language=zh-CN&include_adult=false&page=1"
            val response = try { fetcher.load(searchUrl, token) }
                catch (auth: ArtworkAuthenticationFailure) {
                    rejectedTokens[tokenKey] = System.currentTimeMillis() + 30L * 60 * 1000
                    return null
                } catch (_: Exception) { null }
            if (response == null) {
                cacheNegative(file)
                return null
            }
            val results = runCatching { JSONObject(response).getJSONArray("results") }.getOrNull()
                ?: return null
            val candidate = ArtworkMatch.best(queryTitle, film.year, candidates(results, category))
                ?: run { cacheNegative(file); return null }
            val synopsis = candidate.synopsis.trim().take(1300).takeIf { it.isNotBlank() }
            val rating = candidate.rating?.takeIf { it.isFinite() && it in 0.0..10.0 &&
                candidate.voteCount >= 10 }
            val genres = TmdbGenreClassification.labels(candidate.genreIds)
            val classified = TmdbGenreClassification.category(film.category, category,
                candidate.genreIds)
            val picture = EnrichedArtwork(
                image("w780", candidate.posterPath), image("original", candidate.backdropPath),
                synopsis, candidate.year?.toString(), rating, genres, classified,
                candidate.originalTitle?.take(160)
            )
            if (picture.posterUrl == null && picture.backdropUrl == null && synopsis == null) return null
            persist(file, JSONObject().put("poster", picture.posterUrl ?: "")
                .put("backdrop", picture.backdropUrl ?: "")
                .put("overview", picture.synopsis ?: "")
                .put("year", picture.year ?: "")
                .put("rating", picture.rating ?: JSONObject.NULL)
                .put("genres", JSONArray(picture.genres))
                .put("category", picture.category ?: "")
                .put("originalTitle", picture.originalTitle ?: ""))
            return picture
        } finally {
            networkLimit.release()
        }
    }

    private fun cacheNegative(file: File) = persist(file, JSONObject().put("negative", true))

    private fun persist(file: File, json: JSONObject) {
        runCatching {
            cacheRoot.mkdirs()
            if ((cacheRoot.listFiles()?.size ?: 0) > 300) {
                cacheRoot.listFiles()?.sortedBy { it.lastModified() }?.take(35)?.forEach { it.delete() }
            }
            file.writeText(json.toString())
        }
    }

    private fun decodeCached(obj: JSONObject): EnrichedArtwork? {
        val genreItems = obj.optJSONArray("genres") ?: JSONArray()
        val rating = if (obj.has("rating") && !obj.isNull("rating"))
            obj.optDouble("rating", Double.NaN).takeIf { it.isFinite() && it in 0.0..10.0 }
            else null
        val result = EnrichedArtwork(
            obj.optString("poster").takeIf { isTmdbImage(it) },
            obj.optString("backdrop").takeIf { isTmdbImage(it) },
            obj.optString("overview").takeIf { it.isNotBlank() }?.take(1300),
            obj.optString("year").takeIf { it.matches(Regex("(?:19|20)\\d{2}")) },
            rating,
            (0 until minOf(genreItems.length(), 5)).mapNotNull { index ->
                genreItems.optString(index).takeIf { it.isNotBlank() }?.take(20)
            },
            obj.optString("category").takeIf { it in listOf("电影", "剧集", "动漫", "综艺", "纪录片") },
            obj.optString("originalTitle").takeIf { it.isNotBlank() }?.take(160)
        )
        return result.takeIf { it.posterUrl != null || it.backdropUrl != null || it.synopsis != null }
    }

    private fun candidates(items: JSONArray, category: String): List<ArtworkSearchCandidate> {
        val result = ArrayList<ArtworkSearchCandidate>()
        for (i in 0 until minOf(items.length(), 20)) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.optBoolean("adult", false)) continue
            val date = obj.optString(if (category == "movie") "release_date" else "first_air_date")
            val names = if (category == "movie") listOf(obj.optString("title"),
                obj.optString("original_title")) else listOf(obj.optString("name"),
                obj.optString("original_name"))
            val genres = obj.optJSONArray("genre_ids") ?: JSONArray()
            result += ArtworkSearchCandidate(
                names, Regex("^\\d{4}").find(date)?.value?.toIntOrNull(),
                obj.optString("poster_path").takeIf { acceptablePath(it) },
                obj.optString("backdrop_path").takeIf { acceptablePath(it) },
                synopsis = obj.optString("overview").take(1300),
                rating = obj.optDouble("vote_average", Double.NaN)
                    .takeIf { it.isFinite() && it in 0.0..10.0 },
                voteCount = obj.optInt("vote_count", 0),
                genreIds = (0 until minOf(genres.length(), 15))
                    .map { genres.optInt(it, -1) }.filter { it > 0 },
                originalTitle = obj.optString(if (category == "movie")
                    "original_title" else "original_name").takeIf { it.isNotBlank() }
            )
        }
        return result
    }

    companion object {
        private val networkLimit = Semaphore(3)
        private val rejectedTokens = ConcurrentHashMap<String, Long>()
        private val pathPattern = Regex("^/[a-zA-Z0-9_./-]+\\.(?:jpg|jpeg|png|webp)$")
        private fun acceptablePath(path: String) = pathPattern.matches(path) && ".." !in path
        private fun image(size: String, path: String?) = path?.takeIf { acceptablePath(it) }
            ?.let { "https://image.tmdb.org/t/p/$size$it" }
        private fun isTmdbImage(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host == "image.tmdb.org" && uri.rawUserInfo == null &&
                uri.path.startsWith("/t/p/") && ".." !in uri.path
        }.getOrDefault(false)
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private class ArtworkAuthenticationFailure : Exception()

private object NetworkArtworkFetcher : ArtworkJsonFetcher {
    override fun load(url: String, readAccessToken: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 4500
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $readAccessToken")
        }
        return try {
            if (connection.responseCode in setOf(401, 403)) throw ArtworkAuthenticationFailure()
            if (connection.responseCode != 200 ||
                !connection.contentType.orEmpty().startsWith("application/json")) return null
            if (connection.contentLengthLong > 1_000_000) return null
            connection.inputStream.use { stream ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (data.size() + read > 1_000_000) return null
                    data.write(buffer, 0, read)
                }
                data.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }
}
