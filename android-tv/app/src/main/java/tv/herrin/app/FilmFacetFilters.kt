package tv.herrin.app

/** Each selected facet is intersected with the others (AND); unknown fields never match. */
internal data class FilmFacetSelection(
    val actor: String? = null,
    val director: String? = null,
    val region: String? = null,
    val year: String? = null,
    val category: String? = null,
    val ratingBand: String? = null
) {
    val isEmpty: Boolean get() = actor == null && director == null && region == null &&
        year == null && category == null && ratingBand == null
}

internal object FilmFacetFilters {
    val ratingBands = listOf("≥9.0", "≥8.0", "≥7.0", "≥6.0", "低于6.0", "未评分")

    /** Scores from arbitrary source text cannot be treated as independently verified TMDB values. */
    fun ratingMatches(film: Film, band: String?): Boolean {
        if (band == null) return true
        val score = film.ratingValue?.takeIf { film.ratingProvider == "TMDB" &&
            it.isFinite() && it in 0.0..10.0 }
        if (band == "未评分") return score == null
        if (score == null) return false
        return when (band) {
            "≥9.0" -> score >= 9.0
            "≥8.0" -> score >= 8.0
            "≥7.0" -> score >= 7.0
            "≥6.0" -> score >= 6.0
            "低于6.0" -> score < 6.0
            else -> false
        }
    }

    fun results(films: List<Film>, selected: FilmFacetSelection): List<Film> = films.filter { film ->
        (selected.actor == null || selected.actor in film.actors) &&
            (selected.director == null || selected.director in film.directors) &&
            (selected.region == null || selected.region in film.regions) &&
            (selected.year == null || selected.year == film.year) &&
            (selected.category == null || selected.category == film.category) &&
            ratingMatches(film, selected.ratingBand)
    }

    /** Stable values from the *current* title library; no IMDb/TMDB catalog-wide assumptions. */
    fun actors(films: List<Film>): List<String> = options(films.flatMap { it.actors })
    fun directors(films: List<Film>): List<String> = options(films.flatMap { it.directors })
    fun regions(films: List<Film>): List<String> = options(films.flatMap { it.regions })
    fun years(films: List<Film>): List<String> = films.map { it.year }.filter {
        it.matches(Regex("(?:19|20)\\d{2}"))
    }.distinct().sortedDescending()
    fun categories(films: List<Film>): List<String> =
        listOf("电影", "剧集", "动漫", "综艺", "纪录片").filter { kind ->
            films.any { it.category == kind }
        }

    private fun options(names: List<String>): List<String> = names.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        ).map { it.key }
}
