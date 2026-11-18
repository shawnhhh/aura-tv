package tv.herrin.app

/**
 * An external metadata item is not playable until a source adapter has returned
 * an actual non-blank episode URI. Never fall through to the offline demo path.
 */
internal object HerrinPlaybackEntry {
    fun firstPlayable(film: Film?): Pair<Int, String>? {
        if (film == null) return null
        val seasons = SeasonEpisodes.fromFilm(film)
        val ordered = seasons?.sections?.sortedBy { it.number }?.flatMap { section ->
            section.episodes.sortedBy { it.number }.map { it.source }
        } ?: film.playbackEpisodes.sortedBy { SeasonEpisodes.explicitNumber(it.title) ?: it.number }
        for (episode in ordered) {
            val line = episode.lines.firstOrNull { it.uri.isNotBlank() }
            if (line != null) return episode.number to line.id
        }
        return null
    }

    fun unavailableTmdb(film: Film): Boolean =
        film.id.startsWith("tmdb-") && firstPlayable(film) == null
}
