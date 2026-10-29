package tv.herrin.app

/** TV-style channel zapping in playlist order, not raw URL order. */
internal object LiveChannelNavigation {
    fun preferenceKey(choice: LiveChannelChoice): String = choice.name.trim().lowercase()

    fun chosenLine(choice: LiveChannelChoice, preferredLineUrls: Map<String, String>): LiveChannel =
        choice.options.firstOrNull { it.url == preferredLineUrls[preferenceKey(choice)] } ?: choice.first

    /** Down is the next unique programme, Up is the previous one; both wrap. */
    fun move(channels: List<LiveChannel>, selectedUrl: String, delta: Int,
             preferredLineUrls: Map<String, String> = emptyMap()): LiveChannel? {
        val choices = LiveChannelDiscovery.choices(channels)
        if (choices.isEmpty()) return null
        if (delta == 0) {
            return choices.firstOrNull { choice -> choice.options.any { it.url == selectedUrl } }
                ?.let { chosenLine(it, preferredLineUrls) }
        }
        val index = choices.indexOfFirst { choice -> choice.options.any { it.url == selectedUrl } }
        val targetIndex = if (index == -1) {
            if (delta > 0) 0 else choices.lastIndex
        } else Math.floorMod(index + delta, choices.size)
        return chosenLine(choices[targetIndex], preferredLineUrls)
    }
}
