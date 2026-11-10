package tv.herrin.app

/** A new user selection reacquires a provider result; an old signed Film is never re-signed or replayed. */
internal suspend fun refreshSpiderSelection(original:Film,config:TvBoxConfig.Single?,bridge:TvBoxSpiderBridge,
    allowed:(TvBoxSite)->Boolean):Film? {
    val origin=original.spiderOrigin?:return null
    val site=config?.sites?.firstOrNull{it.key==origin.site &&allowed(it)}?:return null
    val runtime=site.jar?:config.spiderJar?:site.scriptApi.orEmpty()
    if(SpiderRuntimeCompat.sessionKey(runtime,site)!=origin.runtime)return null
    // Local selection identity only: no TMDB request or inferred playback authority.
    val request=HerrinFilmIdentity(original.tmdbTvId?:1,
        ArtworkTitle.identify(original).mediaType?:if(original.category=="剧集")"tv" else "movie",
        listOf(original.originalDisplay?.title.orEmpty(),original.title,original.subtitle).filter(String::isNotBlank),original.year.toIntOrNull())
    if(bridge.browse(site,config,searchOnly=true) !is SpiderResult.Ready)return null
    val fresh=HerrinTvBoxSpiderSource(site,bridge).search(request)
        .singleOrNull{it.film.spiderVodId==original.spiderVodId}?:return null
    val detail=(bridge.details(fresh.film) as? SpiderResult.Ready)?.value?:return null
    return detail.takeIf{HerrinFilmIdentityMatch.matches(request,fresh.copy(film=it))}
}
