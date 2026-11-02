package tv.herrin.app

/**
 * An external metadata item is not playable until a source adapter has returned
 * an actual non-blank episode URI. Never fall through to the offline demo path.
 */
internal object HerrinPlaybackEntry {
    fun firstPlayable(film: Film?): Pair<Int, String>? {
        if (film == null) return null
        for (episode in film.playbackEpisodes) {
            val line = episode.lines.firstOrNull { it.uri.isNotBlank() }
            if (line != null) return episode.number to line.id
        }
        return null
    }

    fun unavailableTmdb(film: Film): Boolean =
        film.id.startsWith("tmdb-") && firstPlayable(film) == null
}
