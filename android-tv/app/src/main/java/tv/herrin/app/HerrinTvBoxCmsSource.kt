package tv.herrin.app

/**
 * A TMDB title can be searched against any user-selected CMS site, including
 * sites imported from more than one warehouse. No new external Spider runtime,
 * plugin or JAR is executed by this source.
 *
 * The resulting source Film retains original cmsVodId and is resolved by the
 * existing TvBoxVodRepository.details / TvBoxCmsPlaybackResolver.
 */
internal class HerrinTvBoxCmsSource(
    internal val site: TvBoxSite,
    private val repo: TvBoxVodRepository,
    private val allowHttp: Boolean
) : HerrinFilmSource {
    override val id: String = "cms:" + (site.apiUrl ?: site.key)
    override val type: HerrinSourceType = HerrinSourceType.TVBOX_CMS

    override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
        val titles = identity.names.filter { it.length in 2..120 }
            .distinct().take(2)
        val matches = linkedMapOf<String, HerrinSourceMovie>()
        for (name in titles) {
            val result = repo.search(site, name, allowHttp)
            if(result is TvBoxVodResult.Error && result.httpCode!=null)throw SourceHttpFailure(result.httpCode,result.retryAfterMs)
            if (result !is TvBoxVodResult.Ready) continue
            for (film in result.films) {
                val categoryType = ArtworkTitle.identify(film).mediaType ?: identity.mediaType
                val entry = HerrinSourceMovie(id, type, film, categoryType,
                    resolutionClaim = HerrinFilmIdentityMatch.resolutionClaim(film.title +
                        " " + film.subtitle),
                    hdrClaim = Regex("""(?i)\b(?:HDR10|HDR|Dolby[ ._-]?Vision|DV)\b""")
                        .containsMatchIn(film.subtitle))
                if (HerrinFilmIdentityMatch.matches(identity, entry))
                    matches[film.cmsVodId ?: film.id] = entry
            }
            if (matches.isNotEmpty()) break
        }
        return matches.values.take(60)
    }

    suspend fun details(movie: HerrinSourceMovie): TvBoxVodDetailResult {
        require(movie.sourceId == id && movie.sourceType == type)
        return repo.details(site, movie.film, allowHttp)
    }

    fun play(film: Film, episode: Int, lineId: String): PlaybackResult =
        TvBoxCmsPlaybackResolver.resolve(film, episode, lineId, allowHttp, site.headers)
}
