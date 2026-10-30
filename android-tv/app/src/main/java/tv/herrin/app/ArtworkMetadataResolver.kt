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
    val category: String? = null, val originalTitle: String? = null,
    val actors: List<String> = emptyList(), val directors: List<String> = emptyList(),
    val regions: List<String> = emptyList(), val releaseDate: String? = null,
    val runtimeMinutes: Int? = null, val people: List<FilmPerson> = emptyList(),
    val tmdbTvId: Int? = null, val displayTitle: String? = null,
    val tmdbId: Int? = null, val mediaType: String? = null
)

/** Conservative identity extraction: standalone technical tags never become a title or a year. */
internal data class ArtworkIdentity(val title: String, val year: String?, val mediaType: String?)

internal object ArtworkTitle {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val technical = "(?:2160[Pp]|1080[Pp]|720[Pp]|[48][Kk]|[Hh][Dd][Rr](?:10\\+?)?|[Dd]olby[ ._-]?[Vv]ision|[Dd][Vv]|杜比视界|杜比|蓝光|超清|高清|国语中字|国语|国配|粤语|双语|中文字幕|中字|字幕|无字幕|简繁|内嵌字幕|[Ww][Ee][Bb][- ]?[Dd][Ll]|[Bb][Dd][Rr]ip|[Bb][Dd]|[Hh][Dd]|[Hh]\\.?26[45]|[Xx]26[45]|[Hh][Ee][Vv][Cc]|[Aa][Aa][Cc]|[Dd][Tt][Ss]|[Aa][Tt][Mm][Oo][Ss]|完整版|完结|全集|更新至?\\d+集)"
    private val brackets = Regex("[\\[【(（]([^\\]】)）]{1,100})[\\]】)）]")
    private val tag = Regex("(?i)(?<![\\p{L}\\p{N}])$technical(?![\\p{L}\\p{N}])")
    private val trailingChineseTag = Regex("(?:国语中字|国语|粤语|国配|中文字幕|中字|无字幕|内嵌字幕|杜比视界|超清|高清|蓝光|完整版|全集|完结)$")
    private val separatedYear = Regex("(?<![\\p{L}\\p{N}])((?:19|20)\\d{2})(?![\\p{L}\\p{N}])")

    fun identify(film: Film): ArtworkIdentity {
        val raw = Normalizer.normalize(film.title.trim(), Normalizer.Form.NFKC)
        val titleYear = separatedYear.findAll(raw).lastOrNull {
            it.range.first > 0 && raw.take(it.range.first).any(Char::isLetter) &&
                it.groupValues[1].toInt() <= java.time.Year.now().value + 1
        }?.groupValues?.get(1)
        val year = Regex("(?:19|20)\\d{2}").find(film.year)?.value ?: titleYear
        val type = when {
            film.category in listOf("电影", "纪录片") -> "movie"
            film.category in listOf("剧集", "电视剧", "动漫", "动画", "综艺") -> "tv"
            film.category.contains("电影") || film.category.equals("movie", true) -> "movie"
            film.category.contains("剧") || film.category.equals("tv", true) || film.episodes > 1 -> "tv"
            else -> null
        }
        return ArtworkIdentity(searchQuery(raw), year, type)
    }

    fun searchQuery(raw: String): String {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        var clean = normalized.replace(brackets) { result ->
            val contents = result.groupValues[1]
            if (tag.containsMatchIn(contents) || contents.matches(Regex("(?:19|20)\\d{2}"))) " "
            else result.value
        }.replace(Regex("[_.·]+"), " ")
        clean = clean.replace(tag, " ")
        // A bare year such as '1917' is a film title. Remove only a separated year beside a name.
        clean = clean.replace(separatedYear) { match ->
            if (match.range.first > 0 && clean.take(match.range.first).any(Char::isLetter) &&
                match.groupValues[1].toInt() <= java.time.Year.now().value + 1) " " else match.value
        }
        var last: String
        do { last = clean; clean = clean.trim().replace(trailingChineseTag, "") } while (last != clean)
        clean = clean.replace(Regex("[ \\t-]+"), " ").trim(' ', '-', '[', ']', '【', '】')
        return clean.ifBlank { normalized }
    }

    fun normalize(raw: String): String = searchQuery(raw).replace(punctuation, "").lowercase(Locale.ROOT)
}

internal data class ArtworkSearchCandidate(
    val names: List<String>, val year: Int?, val posterPath: String?, val backdropPath: String?,
    val synopsis: String = "", val rating: Double? = null,
    val voteCount: Int = 0, val genreIds: List<Int> = emptyList(),
    val originalTitle: String? = null, val tmdbId: Int? = null,
    val releaseDate: String? = null
)

/** Only TMDB's explicitly supplied place and credit fields are accepted. */
internal data class TmdbCredits(
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val regions: List<String> = emptyList(), val releaseDate: String? = null,
    val runtimeMinutes: Int? = null, val people: List<FilmPerson> = emptyList()
)

/** Exact CDN paths only; no profile images from a film source or arbitrary hosts. */
internal object TmdbPortraits {
    private val path = Regex("^/[A-Za-z0-9_./-]+\\.(?:jpg|jpeg|png|webp)$")
    fun url(raw: String?): String? = raw?.takeIf {
        path.matches(it) && ".." !in it && "//" !in it && "/./" !in it
    }?.let { "https://image.tmdb.org/t/p/w185$it" }
}

internal object TmdbCreditsParser {
    fun parse(json: String?, type: String): TmdbCredits {
        val root = runCatching { JSONObject(json ?: return TmdbCredits()) }.getOrNull()
            ?: return TmdbCredits()
        fun names(items: JSONArray?, cap: Int, predicate: (JSONObject) -> Boolean = { true }): List<String> {
            if (items == null) return emptyList()
            return (0 until minOf(items.length(), 150)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                item.optString("name").trim().take(60)
                    .takeIf { predicate(item) && it.isNotBlank() }
            }.distinct().take(cap)
        }
        val credits = root.optJSONObject("credits")
        val actors = names(credits?.optJSONArray("cast"), 8)
        val crew = names(credits?.optJSONArray("crew"), 5) {
            it.optString("job").equals("Director", true)
        }
        val creators = if (type == "tv") names(root.optJSONArray("created_by"), 5)
            else emptyList()
        val directors = (creators + crew).distinct().take(5)
        fun people(items: JSONArray?, limit: Int, kind: String): List<FilmPerson> {
            if (items == null) return emptyList()
            val parsed = ArrayList<FilmPerson>()
            for (index in 0 until minOf(items.length(), 80)) {
                val item = items.optJSONObject(index) ?: continue
                if (kind == "director" && !item.optString("job").equals("Director", true)) continue
                val name = item.optString("name").trim().take(60)
                if (name.isBlank()) continue
                val role = when (kind) {
                    "director" -> "导演"
                    "creator" -> "主创"
                    else -> item.optString("character").trim().take(65).takeIf { it.isNotBlank() }
                        ?.let { "饰 $it" } ?: "演员"
                }
                if (parsed.none { it.name == name })
                    parsed += FilmPerson(name, role, TmdbPortraits.url(item.optString("profile_path")))
                if (parsed.size >= limit) break
            }
            return parsed
        }
        val directorPeople = people(credits?.optJSONArray("crew"), 3, "director")
        val creatorPeople = if (type == "tv") people(root.optJSONArray("created_by"), 2, "creator")
            else emptyList()
        val castPeople = people(credits?.optJSONArray("cast"), 8, "cast")
        val allPeople = (directorPeople + creatorPeople + castPeople)
            .distinctBy { it.name }.take(10)
        val regions = if (type == "tv") {
            val list = root.optJSONArray("origin_country") ?: JSONArray()
            (0 until minOf(list.length(), 12)).mapNotNull { index ->
                val code = list.optString(index).uppercase(Locale.ROOT)
                code.takeIf { it.matches(Regex("[A-Z]{2}")) }
            }.distinct().take(5).map(TmdbRegionNames::label)
        } else {
            val list = root.optJSONArray("production_countries") ?: JSONArray()
            (0 until minOf(list.length(), 12)).mapNotNull { index ->
                val place = list.optJSONObject(index) ?: return@mapNotNull null
                val code = place.optString("iso_3166_1").uppercase(Locale.ROOT)
                code.takeIf { it.matches(Regex("[A-Z]{2}")) }
            }.distinct().take(5).map(TmdbRegionNames::label)
        }
        val release = root.optString(if (type == "movie") "release_date" else "first_air_date")
            .takeIf { it.matches(Regex("(?:19|20)\\d{2}-\\d{2}-\\d{2}")) }
        val movieDuration = root.optInt("runtime", 0).takeIf { it in 1..600 }
        val tvDuration = root.optJSONArray("episode_run_time")?.let { values ->
            (0 until minOf(values.length(), 10)).map { values.optInt(it, 0) }
                .firstOrNull { it in 1..360 }
        }
        return TmdbCredits(actors, directors, regions, release,
            if (type == "tv") tvDuration else movieDuration, allPeople)
    }
}

internal object TmdbRegionNames {
    private val names = mapOf("CN" to "中国大陆", "HK" to "中国香港",
        "TW" to "中国台湾", "MO" to "中国澳门", "US" to "美国",
        "GB" to "英国", "JP" to "日本", "KR" to "韩国", "FR" to "法国",
        "DE" to "德国", "IT" to "意大利", "ES" to "西班牙", "CA" to "加拿大",
        "AU" to "澳大利亚", "IN" to "印度", "TH" to "泰国")
    fun label(code: String): String = names[code] ?: code
}

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
    private val fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher,
    catalogScope: String = ""
) {
    private val corrections = TmdbMatchCorrections(File(cacheRoot, "manual-matches"), catalogScope)
    fun manualMatch(film: Film): TmdbManualMatch? = corrections.get(film)
    fun setManualMatch(film: Film, match: TmdbManualMatch?) = corrections.set(film, match)

    /** A token verified by the official configuration endpoint may be retried
     * immediately after its previous authentication cooldown. */
    fun clearAuthCooldown(token: String) {
        if (token.isNotBlank()) rejectedTokens.remove(hash(token.trim()))
    }

    fun lookup(film: Film, readAccessToken: String, forceRefresh: Boolean = false): EnrichedArtwork? {
        val token = readAccessToken.trim()
        if (!Regex("^[A-Za-z0-9._~-]{20,1000}$").matches(token) || film.title.isBlank()) return null
        val identity = ArtworkTitle.identify(film)
        val queryTitle = identity.title
        if (queryTitle.isBlank()) return null
        val manual = manualMatch(film)
        val category = manual?.mediaType ?: identity.mediaType ?: return null
        val key = hash("detail-v5|$category|$queryTitle|${identity.year}|${manual?.id}|${hash(token)}")
        val file = File(cacheRoot, "$key.json")
        if (!forceRefresh && file.isFile && file.length() in 1..16384) {
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
            val searchUrl = if (manual != null) {
                "https://api.themoviedb.org/3/$category/${manual.id}?append_to_response=credits&language=zh-CN"
            } else "https://api.themoviedb.org/3/search/$category?query=" +
                URLEncoder.encode(queryTitle, "UTF-8") + "&language=zh-CN&include_adult=false&page=1"
            val response = try { fetcher.load(searchUrl, token) }
                catch (auth: ArtworkAuthenticationFailure) {
                    rejectedTokens[tokenKey] = System.currentTimeMillis() + 30L * 60 * 1000
                    return null
                } catch (_: Exception) { null }
            // Transport errors are retryable; a failed network is not a negative title match.
            if (response == null) return null
            val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
            var matchedCategory = category
            val candidate = if (manual != null) {
                if (root.optInt("id") != manual.id || root.optBoolean("adult")) return null
                candidates(JSONArray().put(root), category).singleOrNull()
            } else {
                val results = root.optJSONArray("results") ?: return null
                val primary = ArtworkMatch.best(queryTitle, identity.year.orEmpty(),
                    candidates(results, category))
                if (primary != null) primary else {
                    // TVBox site type_name is often absent/misclassified. Search
                    // the other OFFICIAL TMDB media type only if the first query
                    // was valid but produced no exact, year-consistent match.
                    // A fuzzy or ambiguous hit is never automatically accepted.
                    val other = if (category == "movie") "tv" else "movie"
                    val url = "https://api.themoviedb.org/3/search/$other?query=" +
                        URLEncoder.encode(queryTitle, "UTF-8") +
                        "&language=zh-CN&include_adult=false&page=1"
                    val otherResponse = try { fetcher.load(url, token) }
                        catch (auth: ArtworkAuthenticationFailure) {
                            rejectedTokens[tokenKey] = System.currentTimeMillis() + 30L * 60 * 1000
                            return null
                        } catch (_: Exception) { null }
                    // If the alternate request failed, don't poison the cache:
                    // this is a connection problem, not an absent movie.
                    if (otherResponse == null) return null
                    val otherList = runCatching { JSONObject(otherResponse).optJSONArray("results") }
                        .getOrNull() ?: return null
                    ArtworkMatch.best(queryTitle, identity.year.orEmpty(),
                        candidates(otherList, other)).also {
                        if (it != null) matchedCategory = other
                    }
                }
            } ?: run { cacheNegative(file); return null }
            // Manual corrections already fetched detail; otherwise read the
            // matched category's credits, never the original misclassified tab.
            val detail = if (manual != null) TmdbCreditsParser.parse(response, matchedCategory)
            else candidate.tmdbId?.let { tmdbId ->
                val url = "https://api.themoviedb.org/3/$matchedCategory/$tmdbId" +
                    "?append_to_response=credits&language=zh-CN"
                val creditResponse = runCatching { fetcher.load(url, token) }.getOrNull()
                TmdbCreditsParser.parse(creditResponse, matchedCategory)
            } ?: TmdbCredits()
            val synopsis = candidate.synopsis.trim().take(1300).takeIf { it.isNotBlank() }
            val rating = candidate.rating?.takeIf { it.isFinite() && it in 0.0..10.0 &&
                candidate.voteCount >= 10 }
            val genres = TmdbGenreClassification.labels(candidate.genreIds)
            val classified = TmdbGenreClassification.category(film.category, matchedCategory,
                candidate.genreIds)
            val picture = EnrichedArtwork(
                image("w780", candidate.posterPath), image("original", candidate.backdropPath),
                synopsis, candidate.year?.toString(), rating, genres, classified,
                candidate.originalTitle?.take(160), detail.actors, detail.directors, detail.regions,
                detail.releaseDate ?: candidate.releaseDate, detail.runtimeMinutes, detail.people,
                if (matchedCategory == "tv") candidate.tmdbId else null,
                candidate.names.firstOrNull { it.isNotBlank() }?.take(160), candidate.tmdbId, matchedCategory
            )
            if (picture.posterUrl == null && picture.backdropUrl == null && synopsis == null) return null
            persist(file, JSONObject().put("poster", picture.posterUrl ?: "")
                .put("backdrop", picture.backdropUrl ?: "")
                .put("overview", picture.synopsis ?: "")
                .put("year", picture.year ?: "")
                .put("rating", picture.rating ?: JSONObject.NULL)
                .put("genres", JSONArray(picture.genres))
                .put("category", picture.category ?: "")
                .put("originalTitle", picture.originalTitle ?: "")
                .put("actors", JSONArray(picture.actors))
                .put("directors", JSONArray(picture.directors))
                .put("regions", JSONArray(picture.regions))
                .put("releaseDate", picture.releaseDate ?: "")
                .put("runtimeMinutes", picture.runtimeMinutes ?: JSONObject.NULL)
                .put("tmdbTvId", picture.tmdbTvId ?: JSONObject.NULL)
                .put("displayTitle", picture.displayTitle ?: "")
                .put("tmdbId", picture.tmdbId ?: JSONObject.NULL)
                .put("mediaType", picture.mediaType ?: "")
                .put("people", JSONArray().apply { picture.people.forEach { person ->
                    put(JSONObject().put("name", person.name).put("role", person.role)
                        .put("portrait", person.portraitUrl ?: ""))
                } }))
            return picture
        } finally {
            networkLimit.release()
        }
    }

    private fun cacheNegative(file: File) = persist(file, JSONObject().put("negative", true))

    private fun persist(file: File, json: JSONObject) {
        runCatching {
            cacheRoot.mkdirs()
            val entries = cacheRoot.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
            if (entries.size > 300) entries.sortedBy { it.lastModified() }.take(35).forEach { it.delete() }
            val temporary = File.createTempFile("tmdb-", ".tmp", cacheRoot)
            try {
                temporary.writeText(json.toString())
                check(temporary.renameTo(file))
            } finally { temporary.delete() }
        }
    }

    private fun decodeCached(obj: JSONObject): EnrichedArtwork? {
        val genreItems = obj.optJSONArray("genres") ?: JSONArray()
        val rating = if (obj.has("rating") && !obj.isNull("rating"))
            obj.optDouble("rating", Double.NaN).takeIf { it.isFinite() && it in 0.0..10.0 }
            else null
        fun cachedNames(key: String, max: Int): List<String> {
            val entries = obj.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(entries.length(), max)).mapNotNull { index ->
                entries.optString(index).trim().take(60).takeIf { it.isNotBlank() }
            }.distinct()
        }
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
            obj.optString("originalTitle").takeIf { it.isNotBlank() }?.take(160),
            cachedNames("actors", 8), cachedNames("directors", 5), cachedNames("regions", 5),
            obj.optString("releaseDate").takeIf { it.matches(Regex("(?:19|20)\\d{2}-\\d{2}-\\d{2}")) },
            obj.optInt("runtimeMinutes", 0).takeIf { it in 1..600 },
            (obj.optJSONArray("people") ?: JSONArray()).let { array ->
                (0 until minOf(array.length(), 10)).mapNotNull { index ->
                    val person = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = person.optString("name").trim().take(60)
                    if (name.isBlank()) null else FilmPerson(name,
                        person.optString("role").trim().take(70),
                        person.optString("portrait").takeIf { it.startsWith("https://image.tmdb.org/t/p/w185/") &&
                            TmdbPortraits.url(it.substringAfter("/w185")) == it })
                }
            },
            obj.optInt("tmdbTvId", 0).takeIf { it > 0 },
            obj.optString("displayTitle").takeIf { it.isNotBlank() }?.take(160),
            obj.optInt("tmdbId", 0).takeIf { it > 0 },
            obj.optString("mediaType").takeIf { it in listOf("movie", "tv") }
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
            val genres = obj.optJSONArray("genre_ids") ?: (obj.optJSONArray("genres") ?: JSONArray()).let { values ->
                JSONArray().apply { for (index in 0 until minOf(values.length(), 15))
                    put(values.optJSONObject(index)?.optInt("id", -1) ?: -1) }
            }
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
                    "original_title" else "original_name").takeIf { it.isNotBlank() },
                tmdbId = obj.optInt("id", -1).takeIf { it > 0 },
                releaseDate = date.takeIf { it.matches(Regex("(?:19|20)\\d{2}-\\d{2}-\\d{2}")) }
            )
        }
        return result
    }

    companion object {
        private val networkLimit = Semaphore(3)
        private val rejectedTokens = ConcurrentHashMap<String, Long>()
        private val pathPattern = Regex("^/[a-zA-Z0-9_./-]+\\.(?:jpg|jpeg|png|webp)$")
        private fun acceptablePath(path: String) = pathPattern.matches(path) &&
            ".." !in path && "//" !in path && "/./" !in path
        private fun image(size: String, path: String?) = path?.takeIf { acceptablePath(it) }
            ?.let { "https://image.tmdb.org/t/p/$size$it" }
        private fun isTmdbImage(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host == "image.tmdb.org" && uri.rawUserInfo == null &&
                uri.path.startsWith("/t/p/") && ".." !in uri.path && "//" !in uri.path
        }.getOrDefault(false)
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal class ArtworkAuthenticationFailure : Exception()

internal object NetworkArtworkFetcher : ArtworkJsonFetcher {
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
