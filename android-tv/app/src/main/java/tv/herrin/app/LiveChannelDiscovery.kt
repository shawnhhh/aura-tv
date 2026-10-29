package tv.herrin.app

internal data class LiveChannelChoice(val name: String, val group: String,
                                      val options: List<LiveChannel>) {
    val first: LiveChannel get() = options.first()
}

/** Pure, local-only browsing of the user's M3U channel list. */
internal object LiveChannelDiscovery {
    /** One visible TV programme; keep every distinct URL as a selectable line. */
    fun choices(channels: List<LiveChannel>): List<LiveChannelChoice> {
        val grouped = linkedMapOf<String, MutableList<LiveChannel>>()
        channels.forEach { channel ->
            val normalized = channel.name.trim().lowercase()
                .replace(Regex("[\\s·._-]+"), "")
                .replace(Regex("(?:高清|超清|标清|蓝光|fhd|uhd|hd|4k|1080p)$", RegexOption.IGNORE_CASE), "")
            if (normalized.isNotBlank()) {
                val variants = grouped.getOrPut(normalized) { mutableListOf() }
                if (variants.none { it.url == channel.url }) variants += channel
            }
        }
        return grouped.values.map { entries ->
            LiveChannelChoice(entries.first().name, entries.first().group, entries.toList())
        }
    }

    fun groupedVisible(channels: List<LiveChannel>, selectedGroup: String?, favoritesOnly: Boolean,
                       query: String, isFavorite: (LiveChannel) -> Boolean): List<LiveChannelChoice> {
        val group = selectedGroupOrAll(channels, selectedGroup)
        val keyword = query.trim()
        return choices(channels).filter { choice ->
            (group == null || choice.options.any { it.group.trim() == group }) &&
                (keyword.isBlank() || choice.options.any {
                    it.name.contains(keyword, ignoreCase = true) ||
                        it.tvgId.contains(keyword, ignoreCase = true)
                }) && (!favoritesOnly || choice.options.any(isFavorite))
        }
    }
    // Preserve the playlist's first-seen group and channel order, including
    // genuinely ungrouped channels. null exclusively means "all groups".
    fun groups(channels: List<LiveChannel>): List<String> =
        channels.map { it.group.trim() }.distinct()

    fun selectedGroupOrAll(channels: List<LiveChannel>, requested: String?): String? =
        requested?.takeIf { it in groups(channels) }

    /** Keep the existing positional predicate API for group/favorites clients. */
    fun visible(
        channels: List<LiveChannel>, selectedGroup: String?, favoritesOnly: Boolean,
        isFavorite: (LiveChannel) -> Boolean
    ): List<LiveChannel> = visible(channels, selectedGroup, favoritesOnly, "", isFavorite)

    fun visible(
        channels: List<LiveChannel>, selectedGroup: String?, favoritesOnly: Boolean,
        query: String,
        isFavorite: (LiveChannel) -> Boolean
    ): List<LiveChannel> {
        val group = selectedGroupOrAll(channels, selectedGroup)
        val keyword = query.trim()
        return channels.filter { channel ->
            (group == null || channel.group.trim() == group) &&
                (keyword.isBlank() || channel.name.contains(keyword, ignoreCase = true) ||
                    channel.tvgId.contains(keyword, ignoreCase = true)) &&
                (!favoritesOnly || isFavorite(channel))
        }
    }
}
