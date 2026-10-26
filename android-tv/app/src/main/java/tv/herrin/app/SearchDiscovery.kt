package tv.herrin.app

/**
 * Deterministic on-device browsing of the currently active catalog. No query
 * leaves the TV; switching provider changes the input list, not the filter.
 */
internal object SearchDiscovery {
    val categories = listOf("全部", "电影", "剧集", "动漫")

    fun results(films: List<Film>, rawQuery: String, category: String, newestFirst: Boolean): List<Film> {
        val query = rawQuery.trim()
        val matching = films.filter { film ->
            (category == "全部" || film.category == category) &&
                (query.isBlank() || film.title.contains(query, ignoreCase = true) ||
                    film.subtitle.contains(query, ignoreCase = true))
        }
        return if (newestFirst) matching.sortedWith(
            compareByDescending<Film> { it.year.toIntOrNull() ?: -1 }
                .thenBy { it.title }.thenBy { it.id }
        ) else matching
    }
}
