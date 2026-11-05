/* SPDX-License-Identifier: GPL-3.0-only
 * HERRIN HTTP playback continuation; uses the existing approved source pipeline.
 */
package tv.herrin.app

internal data class HerrinPlaybackCoordinate(val season: Int?, val episode: Int,
                                           val canMapAcrossSources: Boolean = true) {
    companion object {
        fun from(film: Film, number: Int, line: String): HerrinPlaybackCoordinate? {
            val ep = film.playbackEpisodes.firstOrNull { it.number == number } ?: return null
            val selected = ep.lines.firstOrNull { it.id == line } ?: return null
            val pair = SeasonEpisodes.explicitEpisode(ep.title)
            val season = pair?.first ?: SeasonEpisodes.explicitSeason(selected.title)
            val number = pair?.second ?: SeasonEpisodes.explicitNumber(ep.title)
            val movie = film.category == "电影"
            // A provider list ordinal is not a portable episode coordinate.
            // Unknown season/labels can still retry lines in this exact source.
            return HerrinPlaybackCoordinate(season, number ?: ep.number,
                (number != null || (movie && film.playbackEpisodes.size == 1)) && (movie || season != null))
        }
    }
    fun accepts(ep: PlaybackEpisode, line: PlaybackLine): Boolean {
        val pair = SeasonEpisodes.explicitEpisode(ep.title)
        val otherSeason = pair?.first ?: SeasonEpisodes.explicitSeason(line.title)
        val otherEpisode = pair?.second ?: SeasonEpisodes.explicitNumber(ep.title) ?: ep.number
        return canMapAcrossSources && otherEpisode == episode && otherSeason == season
    }
}

/** Finite budget per logical title/episode. Contains identities only, no URLs or credentials. */
internal class HerrinAutomaticPlayback {
    data class Key(val scope: String, val film: String, val coordinate: HerrinPlaybackCoordinate)
    sealed interface Next {
        data class Line(val id: String) : Next
        data object Source : Next
    }
    data class Search(val film: String, val coordinate: HerrinPlaybackCoordinate, val position: Long)
    data class Resume(val film: String, val episode: Int, val line: String, val position: Long)
    data class Attempt(val film: String, val episode: Int, val line: String, val retry: Int,
                       val source: String? = null)

    companion object {
        fun resolutionFailure(reason: String): FongMiPlaybackRecovery.Failure {
            if (listOf("PROXY_IO_POLICY", "PROXY_IO_TLS", "PROXY_IO_MAIN_THREAD", "PROXY_IO_UNKNOWN", "PROXY_IO_FAILURE")
                    .any(reason::contains)) return FongMiPlaybackRecovery.Failure(FongMiPlaybackRecovery.Kind.NETWORK_POLICY)
            val denied = Regex("(?i)HTTP\\s*[:=]?\\s*(401|403|429)\\b").containsMatchIn(reason) ||
                listOf("未授权", "未批准", "安全校验", "MD5", "证书", "会话已", "来源已失效")
                    .any { reason.contains(it, ignoreCase = true) }
            return FongMiPlaybackRecovery.Failure(if (denied)
                FongMiPlaybackRecovery.Kind.AUTHENTICATION else FongMiPlaybackRecovery.Kind.PARSE)
        }
    }
    private var key: Key? = null
    private val failed = linkedSetOf<Pair<String, String>>()
    private val exhausted = linkedSetOf<String>()

    fun next(context: Key, source: String, film: Film, episode: Int, line: String,
             canSearch: Boolean, failure: FongMiPlaybackRecovery.Failure): Next? {
        if (failure.kind in setOf(FongMiPlaybackRecovery.Kind.AUTHENTICATION,
                FongMiPlaybackRecovery.Kind.NETWORK_POLICY)) return null
        if (key != context) { clear(); key = context }
        if (!failed.add(source to line) || failed.size > 12) return null
        val choices = film.playbackEpisodes.firstOrNull { it.number == episode }?.lines.orEmpty()
            .filter { it.uri.isNotBlank() && source to it.id !in failed }
            .sortedByDescending { HerrinFilmIdentityMatch.resolutionClaim(it.title) ?: 0 }
        if (failed.count { it.first == source } < 6)
            choices.firstOrNull()?.let { return Next.Line(it.id) }
        exhausted += source
        return Next.Source.takeIf { canSearch && context.coordinate.canMapAcrossSources && exhausted.size <= 2 }
    }

    fun clear() { key = null; failed.clear(); exhausted.clear() }
}
