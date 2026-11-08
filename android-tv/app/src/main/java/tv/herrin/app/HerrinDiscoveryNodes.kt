package tv.herrin.app

import kotlinx.coroutines.withContext

/** Bind protocol operations to one immutable root/site/API/JAR identity. */
internal object HerrinDiscoveryNodes {
    fun id(root: String, site: TvBoxSite, jar: String?) =
        SourceHealthIndex.digest(root + "|" + TvBoxAutoSites.identity(site, jar) + "|" + site.apiType + "|" + SourceHealthIndex.digest(site.extension))

    fun create(personals: List<HerrinWebDavSource>, cms: List<Triple<String, TvBoxSite, TvBoxConfig.Single?>>,
        spiders: List<TvBoxSite>, root: String, config: TvBoxConfig.Single?,
        identity: HerrinFilmIdentity, repo: TvBoxVodRepository, allowHttp: Boolean,
        bridge: TvBoxSpiderBridge, consent: Long): List<HerrinSourceDiscovery.Node> {
        val nodes = mutableListOf<HerrinSourceDiscovery.Node>()
        personals.forEach { source ->
            var offers = emptyList<HerrinSourceMovie>()
            nodes += HerrinSourceDiscovery.Node(source.id, if(source.type == HerrinSourceType.NAS) "NAS" else "云盘", source.type,
                search = { source.search(identity).also { offers = it } },
                details = { offer ->
                    val playlist = HerrinPersonalPlaylistBuilder.create(offers.ifEmpty { listOf(offer) }, source::playback)
                    HerrinSourceDiscovery.Detail(offer.film.copy(playbackEpisodes = playlist.episodes,
                        episodes = playlist.episodes.size), playlist.mediaByLine)
                }, timeoutMs = 1_500)
        }
        cms.forEach { (origin, site, owner) ->
            val source = HerrinTvBoxCmsSource(site, repo, allowHttp)
            nodes += HerrinSourceDiscovery.Node(id(origin, site, null), site.name, source.type,
                search = { source.searchCandidates(identity) },
                details = { offer -> (source.details(offer) as? TvBoxVodDetailResult.Ready)?.film
                    ?.let { HerrinSourceDiscovery.Detail(it) } }, site = site, config = owner)
        }
        if(config != null) spiders.forEach { site ->
            suspend fun activate(): Boolean {
                check(bridge.configurationTicket() == consent) { "Source consent changed" }
                val started = System.nanoTime()
                val ready = withContext(SpiderRequestBudget(12_000)) {
                    bridge.browse(site, config, searchOnly = true) is SpiderResult.Ready
                }
                DiscoveryProtocolTrace.phase(site.key,SourceSearchStage.ACTIVATE,
                    if(ready) SourceSearchOutcome.VERIFIED else SourceSearchOutcome.ACTIVATE_FAILED,
                    (System.nanoTime()-started)/1_000_000)
                return ready
            }
            val source = HerrinTvBoxSpiderSource(site, bridge, ::activate)
            nodes += HerrinSourceDiscovery.Node(id(root, site, config.spiderJar), site.name, source.type,
                search = { source.searchCandidates(identity) },
                details = { offer ->
                    // Aggregation switches sessions. Reacquire the exact provider VOD after
                    // restart, never re-sign a stale Film or substitute the TMDB id.
                    if (!activate()) null else {
                        val fresh = if(bridge.isApprovedSourceFilm(offer.film)) offer else
                            HerrinTvBoxSpiderSource(site, bridge).searchCandidates(identity)
                                .firstOrNull { it.film.spiderVodId == offer.film.spiderVodId }
                        fresh?.let { (bridge.details(it.film) as? SpiderResult.Ready)?.value }
                            ?.let { HerrinSourceDiscovery.Detail(it) }
                    }
                }, site = site, config = config)
        }
        return nodes.distinctBy { it.id }
    }
}
