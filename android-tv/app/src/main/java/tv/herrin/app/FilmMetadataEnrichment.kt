package tv.herrin.app

data class FilmDisplayOrigin(
    val subtitle: String, val synopsis: String, val category: String, val year: String,
    val artworkUrl: String?, val backdropUrl: String?, val genres: List<String>,
    val ratingValue: Double?, val ratingProvider: String?,
    val actors: List<String>, val directors: List<String>, val regions: List<String>
)

/**
 * A stream provider supplies source IDs, episode IDs and playback URLs.
 * A metadata provider supplies only display information; these identities
 * must never be rewritten by a fuzzy movie-title match.
 */
internal object FilmMetadataEnrichment {
    fun apply(original: Film, metadata: EnrichedArtwork): Film = original.copy(
        subtitle = metadata.originalTitle?.takeIf { it.isNotBlank() &&
            ArtworkTitle.normalize(it) != ArtworkTitle.normalize(original.title) }
            ?: metadata.genres.joinToString(" · ").ifBlank { original.subtitle },
        synopsis = metadata.synopsis?.takeIf { it.isNotBlank() } ?: original.synopsis,
        category = metadata.category?.takeIf { it.isNotBlank() } ?: original.category,
        year = original.year.ifBlank { metadata.year.orEmpty() },
        artworkUrl = metadata.posterUrl ?: original.artworkUrl,
        backdropUrl = metadata.backdropUrl ?: original.backdropUrl,
        genres = metadata.genres.ifEmpty { original.genres },
        ratingValue = metadata.rating ?: original.ratingValue,
        ratingProvider = if (metadata.rating != null) "TMDB" else original.ratingProvider,
        actors = metadata.actors.ifEmpty { original.actors },
        directors = metadata.directors.ifEmpty { original.directors },
        regions = metadata.regions.ifEmpty { original.regions },
        metadataSource = "TMDB",
        originalDisplay = original.originalDisplay ?: FilmDisplayOrigin(
            original.subtitle, original.synopsis, original.category, original.year, original.artworkUrl,
            original.backdropUrl, original.genres, original.ratingValue, original.ratingProvider,
            original.actors, original.directors, original.regions)
    )

    fun restore(film: Film): Film {
        val saved = film.originalDisplay ?: return film
        return film.copy(subtitle = saved.subtitle, synopsis = saved.synopsis, category = saved.category,
            year = saved.year, artworkUrl = saved.artworkUrl,
            backdropUrl = saved.backdropUrl, genres = saved.genres,
            ratingValue = saved.ratingValue, ratingProvider = saved.ratingProvider,
            actors = saved.actors, directors = saved.directors, regions = saved.regions,
            metadataSource = null, originalDisplay = null)
    }

    fun ratingLabel(film: Film): String? = film.ratingValue
        ?.takeIf { it in 0.0..10.0 && film.ratingProvider == "TMDB" }
        ?.let { "TMDB ${"%.1f".format(java.util.Locale.ROOT, it)}/10" }
}

/** TMDB's genre IDs are metadata, never third-party playback categories. */
internal object TmdbGenreClassification {
    private val labels = mapOf(
        12 to "冒险", 14 to "奇幻", 16 to "动画", 18 to "剧情", 27 to "恐怖",
        28 to "动作", 35 to "喜剧", 36 to "历史", 37 to "西部", 53 to "惊悚",
        80 to "犯罪", 99 to "纪录", 878 to "科幻", 9648 to "悬疑",
        10402 to "音乐", 10749 to "爱情", 10751 to "家庭", 10752 to "战争",
        10759 to "动作冒险", 10762 to "儿童", 10763 to "新闻", 10764 to "真人秀",
        10765 to "科幻奇幻", 10766 to "肥皂剧", 10767 to "脱口秀", 10768 to "战争政治"
    )

    fun labels(ids: List<Int>): List<String> = ids.distinct().mapNotNull(labels::get).take(5)

    fun category(original: String, tmdbType: String, ids: List<Int>): String = when {
        99 in ids -> "纪录片"
        16 in ids -> "动漫"
        10764 in ids || 10767 in ids -> "综艺"
        original in listOf("动漫", "纪录片", "综艺") -> original
        tmdbType == "tv" -> "剧集"
        else -> "电影"
    }
}
