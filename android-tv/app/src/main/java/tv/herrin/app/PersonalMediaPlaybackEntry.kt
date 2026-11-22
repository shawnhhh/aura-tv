package tv.herrin.app

/** Selection metadata only. The existing indexed playback effect reopens the saved connection. */
internal object PersonalMediaPlaybackEntry {
    data class Selection(val film: Film, val episode: Int, val line: String)
    fun from(item: Film): Selection? {
        if (!item.id.startsWith("personal-index:")) return null
        val file = item.id.removePrefix("personal-index:").takeIf(String::isNotBlank) ?: return null
        val first = HerrinPlaybackEntry.firstPlayable(item) ?: return null
        val selected = item.playbackEpisodes.firstOrNull { it.number == first.first }
            ?.lines?.firstOrNull { it.id == first.second } ?: return null
        val selectedFile = selected.id.takeIf { it.startsWith("personal-file:") }
            ?.removePrefix("personal-file:")?.takeIf(String::isNotBlank) ?: return null
        if (selected.uri != "personal-index:$selectedFile") return null
        return Selection(item.copy(id = "personal-$file", cmsVodId = null, spiderVodId = null, spiderOrigin = null),
            first.first, first.second)
    }
}
