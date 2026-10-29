package tv.herrin.app

/**
 * A display-only season index over a *single* source Film. None of the source identities,
 * stream URLs, or list indexes are rewritten. A season-line in a CMS playlist is a different
 * playback path from the same numbered episode on the other season-line.
 */
internal data class SeasonEpisode(
    val season: Int,
    val number: Int,
    val source: PlaybackEpisode,
    val lines: List<PlaybackLine>
) {
    val displayTitle: String get() = "第 $number 集"
    fun preferredLine(previousLineId: String?): PlaybackLine =
        lines.firstOrNull { it.id == previousLineId } ?: lines.first()
}

internal data class SeasonSection(val number: Int, val episodes: List<SeasonEpisode>)
internal data class SeasonLayout(val sections: List<SeasonSection>) {
    fun find(season: Int, number: Int): SeasonEpisode? =
        sections.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == number }
}

internal data class SeasonResume(val entry: SeasonEpisode, val line: PlaybackLine,
                                 val position: WatchPosition)

/** The original id/index/lineId triple must reach every existing player implementation intact. */
internal object SeasonPlayback {
    fun target(film: Film, entry: SeasonEpisode, line: PlaybackLine): PlaybackRequest {
        require(film.playbackEpisodes.any { it.number == entry.source.number && it === entry.source })
        require(entry.lines.any { it.id == line.id && it.uri == line.uri })
        return PlaybackRequest(film.id, entry.source.number, line.id)
    }

    fun latest(layout: SeasonLayout, load: (Int, String) -> WatchPosition?): SeasonResume? =
        layout.sections.asSequence().flatMap { section -> section.episodes.asSequence() }
            .flatMap { entry -> entry.lines.asSequence().mapNotNull { line ->
                load(entry.source.number, line.id)?.let { SeasonResume(entry, line, it) }
            } }.maxByOrNull { it.position.updatedAtMs }
}

internal object SeasonEpisodes {
    private val chinesePair = Regex("^第\\s*(\\d{1,2})\\s*季\\s*(?:第\\s*)?(\\d{1,3})\\s*[集话]$", RegexOption.IGNORE_CASE)
    private val latinPair = Regex("^S(\\d{1,2})[ ._-]*E(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val englishPair = Regex("^Season\\s*(\\d{1,2})\\s*[/·,._ -]*\\s*Episode\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val seasonOnlyChinese = Regex("^第\\s*(\\d{1,2})\\s*季$")
    private val seasonOnlyEnglish = Regex("^Season\\s*(\\d{1,2})$", RegexOption.IGNORE_CASE)
    private val episodeOnlyChinese = Regex("^第\\s*(\\d{1,3})\\s*[集话]$")
    private val episodeOnlyEnglish = Regex("^(?:E|EP|Episode)\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val episodeOnlyNumber = Regex("^(\\d{1,3})\\s*(?:集|话)?$")

    internal fun explicitEpisode(text: String): Pair<Int, Int>? =
        sequenceOf(chinesePair, latinPair, englishPair).mapNotNull { pattern ->
            pattern.matchEntire(text.trim())?.let {
                it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            }
        }.firstOrNull()?.let { (season, number) ->
            if (season != null && number != null && season in 1..99 && number in 1..999)
                season to number else null
        }

    internal fun explicitSeason(text: String): Int? =
        sequenceOf(seasonOnlyChinese, seasonOnlyEnglish).mapNotNull { regex ->
            regex.matchEntire(text.trim())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()?.takeIf { it in 1..99 }

    internal fun explicitNumber(text: String): Int? =
        sequenceOf(episodeOnlyChinese, episodeOnlyEnglish, episodeOnlyNumber).mapNotNull { regex ->
            regex.matchEntire(text.trim())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()?.takeIf { it in 1..999 }

    /** A single reliable season is *not* a season selector; retain the existing UI. */
    fun fromFilm(film: Film): SeasonLayout? {
        if (film.playbackEpisodes.isEmpty()) return null
        data class Indexed(val season: Int, val number: Int, val source: PlaybackEpisode, val line: PlaybackLine)
        val indexed = film.playbackEpisodes.flatMap { episode ->
            val episodePair = explicitEpisode(episode.title)
            episode.lines.mapNotNull { line ->
                val lineSeason = explicitSeason(line.title)
                // Explicitly contradictory hints must not route the wrong season's stream.
                if (episodePair != null && lineSeason != null && episodePair.first != lineSeason)
                    return@mapNotNull null
                val season = episodePair?.first ?: lineSeason ?: return@mapNotNull null
                val number = episodePair?.second ?: explicitNumber(episode.title)
                    ?: return@mapNotNull null
                Indexed(season, number, episode, line)
            }
        }
        if (indexed.map { it.season }.distinct().size < 2) return null
        val entries = indexed.groupBy { it.season to it.number }.mapNotNull { (coordinate, rows) ->
            // A duplicate season/episode pointing to two source indexes is ambiguous.
            if (rows.map { it.source.number }.distinct().size != 1) return null
            SeasonEpisode(coordinate.first, coordinate.second, rows.first().source,
                rows.map { it.line }.distinctBy { it.id })
        }
        if (entries.size != indexed.map { it.season to it.number }.distinct().size) return null
        val sections = entries.groupBy { it.season }.toSortedMap().map { (number, episodes) ->
            SeasonSection(number, episodes.sortedBy { it.number })
        }
        return SeasonLayout(sections).takeIf { it.sections.size >= 2 }
    }
}
