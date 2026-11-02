package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Curated categories come from TMDB, never from a TVBox provider homepage. */
internal data class HerrinTmdbShelf(
    val key: String, val title: String, val films: List<Film>,
    val isComplete: Boolean = true
)

/**
 * Official TMDB v3 metadata endpoints only. Each item has a stable
 * tmdb-movie-ID / tmdb-tv-ID identity independent of all local/cloud sources.
 * Nothing in this class discovers or claims that media is playable.
 */
internal class HerrinTmdbDiscovery(
    private val fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Cached(val until: Long, val entries: List<Film>)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val base = "https://api.themoviedb.org/3"
    private val safePoster = Regex("""^/[A-Za-z0-9_.-]{1,140}\.(?:jpg|jpeg|png|webp)$""")

    data class Section(val key: String, val title: String, val endpoint: String,
                       val kind: String)

    /** TOP250 is explicitly TMDB Top Rated, never the licensed IMDb Top 250. */
    val sections = listOf(
        Section("trending-movies", "本周热门电影", "/trending/movie/week", "movie"),
        Section("trending-tv", "本周热播剧集", "/trending/tv/week", "tv"),
        Section("new-movies", "最新上映", "/movie/now_playing", "movie"),
        Section("on-air-tv", "近期在播", "/tv/on_the_air", "tv"),
        Section("top250", "TMDB 高分电影 Top 250", "/movie/top_rated", "movie"),
        Section("sci-fi", "科幻电影", "/discover/movie?with_genres=878&sort_by=popularity.desc", "movie"),
        Section("action", "动作电影", "/discover/movie?with_genres=28&sort_by=popularity.desc", "movie"),
        Section("mystery", "悬疑电影", "/discover/movie?with_genres=9648&sort_by=popularity.desc", "movie"),
        Section("comedy", "喜剧电影", "/discover/movie?with_genres=35&sort_by=popularity.desc", "movie"),
        Section("animation", "动漫", "/discover/tv?with_genres=16&sort_by=popularity.desc", "tv"),
        Section("documentary", "纪录片", "/discover/movie?with_genres=99&sort_by=popularity.desc", "movie")
    )

    /** Fast first paint: at most 20 film cards per section, no 250-item startup burst. */
    suspend fun load(section: Section, token: String, page: Int = 1): HerrinTmdbShelf =
        withContext(Dispatchers.IO) {
            require(section in sections && page in 1..13)
            if (token.isBlank()) return@withContext HerrinTmdbShelf(section.key, section.title, emptyList(), false)
            val cacheKey = section.key + ":" + page + ":" + token.hashCode()
            val existing = cache[cacheKey]
            if (existing != null && existing.until > clock())
                return@withContext HerrinTmdbShelf(section.key, section.title, existing.entries)
            val endpoint = section.endpoint + (if ('?' in section.endpoint) "&" else "?") +
                "language=zh-CN&page=" + page + "&include_adult=false"
            val films = parsePage(fetcher.load(base + endpoint, token), section.kind)
            cache[cacheKey] = Cached(clock() + 6 * 60 * 60 * 1000L, films)
            HerrinTmdbShelf(section.key, section.title, films, films.isNotEmpty())
        }

    /** Only call on opening Top250 rail: 13 pages, capped to EXACTLY 250. */
    suspend fun loadTop250(token: String): HerrinTmdbShelf {
        val section = sections.first { it.key == "top250" }
        val all = mutableListOf<Film>()
        var complete = true
        for (page in 1..13) {
            val next = load(section, token, page)
            if (!next.isComplete) { complete = false; break }
            all += next.films
            if (all.size >= 250) break
        }
        return HerrinTmdbShelf(section.key, section.title,
            all.distinctBy(Film::id).take(250), complete && all.size >= 250)
    }

    /**
     * Existing official details/credits parser. Never request source playback
     * fields or accept TMDB IDs supplied by a warehouse config.
     */
    suspend fun detail(film: Film, token: String): Film = withContext(Dispatchers.IO) {
        val pair = identity(film) ?: return@withContext film
        if (token.isBlank()) return@withContext film
        val type = pair.first
        val id = pair.second
        val url = base + "/" + type + "/" + id +
            "?language=zh-CN&append_to_response=credits,external_ids"
        val json = fetcher.load(url, token) ?: return@withContext film
        val obj = JSONObject(json)
        if (obj.optInt("id") != id) return@withContext film
        val credits = TmdbCreditsParser.parse(json, type)
        val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")
            ?.takeIf { it.matches(Regex("""tt\d{7,10}""")) }
            ?: obj.optString("imdb_id").takeIf {
                it.matches(Regex("""tt\d{7,10}"""))
            }
        film.copy(
            imdbId = imdbId ?: film.imdbId,
            synopsis = obj.optString("overview").take(2_000).ifBlank { film.synopsis },
            actors = credits.actors.ifEmpty { film.actors },
            directors = credits.directors.ifEmpty { film.directors },
            people = credits.people.ifEmpty { film.people },
            regions = credits.regions.ifEmpty { film.regions },
            releaseDate = credits.releaseDate ?: film.releaseDate,
            runtimeMinutes = credits.runtimeMinutes ?: film.runtimeMinutes
        )
    }

    fun identity(film: Film): Pair<String, Int>? {
        val components = film.id.split('-')
        if (components.size != 3 || components[0] != "tmdb") return null
        val type = components[1]
        val number = components[2].toIntOrNull() ?: return null
        return if (type in listOf("movie", "tv") && number > 0) type to number else null
    }

    fun parsePage(raw: String?, type: String): List<Film> {
        if (type !in listOf("movie", "tv") || raw == null ||
            raw.toByteArray(Charsets.UTF_8).size > 1_000_000) return emptyList()
        val results = runCatching { JSONObject(raw).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        return (0 until minOf(20, results.length())).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            if (item.optBoolean("adult")) return@mapNotNull null
            val title = item.optString(if (type == "movie") "title" else "name").trim().take(120)
            if (title.isEmpty()) return@mapNotNull null
            val original = item.optString(if (type == "movie") "original_title" else "original_name")
                .trim().take(120)
            val date = item.optString(if (type == "movie") "release_date" else "first_air_date")
            val year = date.take(4).takeIf { it.matches(Regex("""(?:19|20)\d\d""")) }.orEmpty()
            val genreJson = item.optJSONArray("genre_ids") ?: JSONArray()
            val ids = (0 until minOf(genreJson.length(), 8)).map { genreJson.optInt(it) }
            val genres = TmdbGenreClassification.labels(ids)
            val score = item.optDouble("vote_average", Double.NaN)
                .takeIf { it.isFinite() && it in 0.0..10.0 && item.optInt("vote_count") > 0 }
            fun posterPath(field: String, size: String) =
                item.optString(field).takeIf(safePoster::matches)?.let {
                    "https://image.tmdb.org/t/p/" + size + it
                }
            Film(
                id = "tmdb-" + type + "-" + id,
                title = title,
                subtitle = original.takeIf { it != title }.orEmpty(),
                category = TmdbGenreClassification.category(if (type == "movie") "电影" else "剧集", type, ids),
                year = year,
                synopsis = item.optString("overview").trim().take(2_000),
                artwork = R.drawable.photofilm_orbit,
                artworkUrl = posterPath("poster_path", "w500"),
                backdropUrl = posterPath("backdrop_path", "w1280"),
                genres = genres, ratingValue = score,
                ratingProvider = if (score != null) "TMDB" else null,
                metadataSource = "TMDB",
                releaseDate = date.takeIf { it.matches(Regex("""\d\d\d\d-\d\d-\d\d""")) },
                tmdbTvId = if (type == "tv") id else null
            )
        }.distinctBy(Film::id)
    }
}
