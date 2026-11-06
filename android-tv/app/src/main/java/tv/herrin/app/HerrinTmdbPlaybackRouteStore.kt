package tv.herrin.app

/**
 * In-memory, title-scoped playback provenance. Switching from a CMS film to
 * NAS or another CMS must never make the first film play via the new source.
 * No tokens, file URLs or secret headers are persisted by this index.
 */
internal class HerrinTmdbPlaybackRouteStore {
    sealed interface Route {
        data object Personal : Route
        data class Cms(val site: TvBoxSite) : Route
        data class Spider(val approvedSiteKey: String, val sourceFilm: Film? = null) : Route
    }
    private val routes = linkedMapOf<String, Route>()

    fun personal(id: String) { routes[id] = Route.Personal }
    fun cms(id: String, site: TvBoxSite) { routes[id] = Route.Cms(site) }
    fun spider(id: String, approvedKey: String, sourceFilm: Film? = null) {
        require(approvedKey.isNotBlank())
        routes[id] = Route.Spider(approvedKey, sourceFilm)
    }
    /** TMDB identity/display never replaces the signed provider Film or its VOD. */
    fun sourceFilm(id: String): Film? = (routes[id] as? Route.Spider)?.sourceFilm
    fun forFilm(id: String): Route? = routes[id]

    /** A stale Spider key is never sufficient to authorize a new playback. */
    fun usable(id: String, activeApprovedSpiderKey: String?): Route? =
        when (val route = routes[id]) {
            is Route.Spider -> route.takeIf { it.approvedSiteKey == activeApprovedSpiderKey }
            else -> route
        }

    fun forget(id: String) { routes.remove(id) }
    fun clear() { routes.clear() }
}
