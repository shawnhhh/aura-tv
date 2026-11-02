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
        var count = 0
        val episodes = groups.entries.take(150).map { (coordinate, entries) ->
            val ordinal = ++count
            val lines = entries.take(12).mapIndexed { index, (offer, media) ->
                val id = "personal-$ordinal-$index"
                urls[id] = media
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
