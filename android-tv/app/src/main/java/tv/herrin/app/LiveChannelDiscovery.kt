package tv.herrin.app

/** Pure, local-only browsing of the user's M3U channel list. */
internal object LiveChannelDiscovery {
    // Preserve the playlist's first-seen group and channel order, including
    // genuinely ungrouped channels. null exclusively means "all groups".
    fun groups(channels: List<LiveChannel>): List<String> =
        channels.map { it.group.trim() }.distinct()

    fun selectedGroupOrAll(channels: List<LiveChannel>, requested: String?): String? =
        requested?.takeIf { it in groups(channels) }

    fun visible(
        channels: List<LiveChannel>, selectedGroup: String?, favoritesOnly: Boolean,
        isFavorite: (LiveChannel) -> Boolean
    ): List<LiveChannel> {
        val group = selectedGroupOrAll(channels, selectedGroup)
        return channels.filter { channel ->
            (group == null || channel.group.trim() == group) &&
                (!favoritesOnly || isFavorite(channel))
        }
    }
}
