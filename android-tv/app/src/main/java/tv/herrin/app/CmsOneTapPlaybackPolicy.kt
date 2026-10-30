package tv.herrin.app

/**
 * One-tap browsing policy for a selected, explicitly configured CMS site.
 *
 * Most CMS title listings have artwork/title and a vod_id, but no media URL.
 * Only that *selected* title may fetch its own ac=detail&ids=... payload
 * inside the player's normal loading surface. Do not bulk-resolve a catalog,
 * guess a URL, or execute another site's plugin.
 */
internal object CmsOneTapPlaybackPolicy {
    fun resolveSelectedFilmInPlayer(
        siteSelected: Boolean,
        groupedSeason: Boolean,
        spiderActive: Boolean,
        cmsVodId: String?,
        hasPlayableEpisode: Boolean
    ): Boolean =
        siteSelected && !groupedSeason && !spiderActive &&
            !cmsVodId.isNullOrBlank() && !hasPlayableEpisode

    /**
     * Pick the first episode with an actual URL accepted by the licensed
     * Media3 resolver. A CMS may list an opaque/HTTP link first and a valid
     * HTTPS direct stream second; the old first-line heuristic selected the
     * unplayable entry and made a one-tap film appear broken.
     */
    fun firstPlayable(film: Film): Pair<Int, String>? =
        film.playbackEpisodes.firstNotNullOfOrNull { episode ->
            episode.lines.firstOrNull { AuthorizedUrls.https(it.uri) != null }
                ?.let { episode.number to it.id }
        }
}
