package tv.herrin.app

/**
 * Searches only the currently active Spider already explicitly applied by
 * the user. No implicit JAR/JS install, warehouse switching or bootstrap.
 */
internal class HerrinTvBoxSpiderSource(
    internal val site: TvBoxSite,
    private val bridge: TvBoxSpiderBridge
) : HerrinFilmSource {
    override val id = "spider:" + site.key
    override val type = HerrinSourceType.TVBOX_SPIDER

    override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
        val found = linkedMapOf<String, HerrinSourceMovie>()
        for (name in identity.names.filter { it.trim().length >= 2 }.distinct().take(2)) {
            val search = bridge.search(site, name)
            if(search is SpiderResult.Failure)throw SourceCandidateFailure(SourceSearchStage.SEARCH,SourceSearchOutcome.ERROR)
            val films = (search as? SpiderResult.Ready)?.value.orEmpty()
            for (film in films) {
                val kind = ArtworkTitle.identify(film).mediaType ?: identity.mediaType
                val offered = HerrinSourceMovie(
                    sourceId = id, sourceType = type, film = film,
                    mediaType = kind,
                    resolutionClaim = HerrinFilmIdentityMatch.resolutionClaim(
                        film.title + " " + film.subtitle)
                )
                if (HerrinFilmIdentityMatch.matches(identity, offered))
                    found[film.spiderVodId ?: film.id] = offered
            }
            if (found.isNotEmpty()) break
        }
        return found.values.take(60)
    }

    suspend fun details(movie: HerrinSourceMovie): SpiderResult<Film> {
        require(movie.sourceId == id && movie.sourceType == type)
        return bridge.details(movie.film)
    }
}
