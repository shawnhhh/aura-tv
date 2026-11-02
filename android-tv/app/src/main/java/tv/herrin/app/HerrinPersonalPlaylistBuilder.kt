package tv.herrin.app

/**
 * Converts preferred personal media matches into the original Herrin episode
 * and line contract. This preserves source alternatives without falsely
 * claiming any 4K/HDR signal was actually decoded.
 */
internal data class HerrinPersonalPlaylist(
    val episodes: List<PlaybackEpisode>,
    val mediaByLine: Map<String, PlaybackMedia>
)

internal object HerrinPersonalPlaylistBuilder {
    fun create(
        options: List<HerrinSourceMovie>,
        resolve: (HerrinSourceMovie) -> PlaybackResult
    ): HerrinPersonalPlaylist {
        val valid = options.take(160).mapNotNull { item ->
            val playable = resolve(item) as? PlaybackResult.Ready ?: return@mapNotNull null
            item to playable.media
        }.distinctBy { it.second.uri }.take(150)
        if (valid.isEmpty()) return HerrinPersonalPlaylist(emptyList(), emptyMap())
        val tv = valid.first().first.mediaType == "tv"
        val groups = if (tv) valid.groupBy {
            (it.first.season ?: 1) to (it.first.episode ?: 1)
        }.toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        else mapOf((1 to 1) to valid)
        val urls = linkedMapOf<String, PlaybackMedia>()
        // Alternate video files are safe for automatic retry only when they
        // belong to the same authorized source and exact HTTP origin/headers.
        // Never replay a NAS/Cloud Authorization header to another provider.
        fun sameOrigin(first: String, second: String): Boolean = runCatching {
            val a = java.net.URI(first)
            val b = java.net.URI(second)
            a.scheme.equals(b.scheme, true) &&
                a.host != null && a.host.equals(b.host, true) && a.port == b.port &&
                a.rawUserInfo == null && b.rawUserInfo == null
        }.getOrDefault(false)
        var count = 0
        val episodes = groups.entries.take(150).map { (coordinate, entries) ->
            val ordinal = ++count
            val lines = entries.take(12).mapIndexed { index, (offer, media) ->
                val id = "personal-$ordinal-$index"
                val fallbacks = entries.filter { other ->
                    other.first.sourceId == offer.sourceId &&
                        other.second.headers == media.headers &&
                        other.second.allowHttpRedirects == media.allowHttpRedirects &&
                        sameOrigin(media.uri, other.second.uri) &&
                        other.second.uri != media.uri
                }.map { it.second.uri }.distinct().take(5)
                urls[id] = media.copy(alternateUris = (media.alternateUris.filter {
                    sameOrigin(media.uri, it)
                } + fallbacks).distinct().take(5))
                val quality = offer.resolutionClaim?.let { if (it >= 2160) "4K" else it.toString() + "p" }
                    ?: "原画"
                val hdr = if (offer.hdrClaim) " HDR（源标称）" else ""
                PlaybackLine(id,
                    (if (offer.sourceType == HerrinSourceType.NAS) "NAS" else "云盘") +
                        " · " + quality + hdr, media.uri)
            }
            PlaybackEpisode(ordinal,
                if (tv) "S" + coordinate.first + "E" + coordinate.second else "正片",
                lines)
        }
        return HerrinPersonalPlaylist(episodes, urls)
    }
}
