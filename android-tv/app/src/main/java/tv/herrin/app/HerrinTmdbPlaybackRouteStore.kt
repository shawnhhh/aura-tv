package tv.herrin.app

/**
 * In-memory, title-scoped playback provenance. Switching from a CMS film to
 * NAS or another CMS must never make the first film play via the new source.
 * No tokens, file URLs or secret headers are persisted by this index.
 */
internal class HerrinTmdbPlaybackRouteStore(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    sealed interface Route {
        data object Personal : Route
        data class Cms(val site: TvBoxSite) : Route
        data class Spider(val approvedSiteKey: String, val sourceFilm: Film? = null) : Route
    }
    private val routes = linkedMapOf<String, Route>()
    private val checkedAt=linkedMapOf<String,Long>()

    fun personal(id: String) { routes[id] = Route.Personal;checkedAt[id]=clock() }
    fun cms(id: String, site: TvBoxSite) { routes[id] = Route.Cms(site);checkedAt[id]=clock() }
    fun spider(id: String, approvedKey: String, sourceFilm: Film? = null) {
        require(approvedKey.isNotBlank())
        routes[id] = Route.Spider(approvedKey, sourceFilm)
        checkedAt[id]=clock()
    }
    /** TMDB identity/display never replaces the signed provider Film or its VOD. */
    fun sourceFilm(id: String): Film? = (routes[id] as? Route.Spider)?.sourceFilm
    fun forFilm(id: String): Route? = routes[id]

    /** A stale Spider key is never sufficient to authorize a new playback. */
    fun usable(id: String, activeApprovedSpiderKey: String?): Route? =
        when (val route = routes[id]?.takeIf{route->
            val age=clock()-(checkedAt[id]?:Long.MIN_VALUE)
            age in 0..when(route){is Route.Spider->60_000L;Route.Personal->30_000L;else->300_000L}
        }) {
            is Route.Spider -> route.takeIf { it.approvedSiteKey == activeApprovedSpiderKey }
            else -> route
        }

    fun forget(id: String) { routes.remove(id);checkedAt.remove(id) }
    fun clear() { routes.clear();checkedAt.clear() }
}
