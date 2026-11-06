package tv.herrin.app

/** The same ordered candidates drive the recommendation rail and its Hero. */
internal object BrowseHeroSelection {
    fun candidates(category: String, catalog: List<Film>, top250: List<Film>): List<Film> =
        if (category == "Top 250") top250 else catalog.filter {
            category == "首页" || it.category == category || category in it.genres
        }
    fun select(candidates: List<Film>, rememberedId: String?): Film? =
        candidates.firstOrNull { it.id == rememberedId } ?: candidates.firstOrNull()
}

/** Public navigation identity only: never captures resolved media or source credentials. */
internal data class PlaybackReturnContext(val screen: String, val category: String, val focus: String, val film: String)
