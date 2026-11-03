package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded search over sites already covered by an explicitly trusted, pinned
 * TVBox Spider runtime. Activation/search/details/playerContent are verified in
 * order. A site with only a pretty poster or a broken proxy is NOT accepted.
 *
 * Quality ordering is a claimed-label heuristic, never verified video output.
 */
internal object HerrinApprovedSpiderBatch {
    const val PAGE_SIZE = 6

    interface Session {
        suspend fun activate(site: TvBoxSite): Boolean
        suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity): List<HerrinSourceMovie>
        suspend fun details(candidate: HerrinSourceMovie): Film?
        suspend fun canPlay(detailed: Film): Boolean
    }

    data class Match(val site: TvBoxSite, val detailed: Film, val claim: Int,
                     val candidate: HerrinSourceMovie)
    data class Scan(val match: Match?, val checked: Int, val eligible: Int,
                    val nextOffset: Int, val candidatesWithEpisodes: Int)

    suspend fun scan(
        identity: HerrinFilmIdentity,
        sites: List<TvBoxSite>,
        allowed: (TvBoxSite) -> Boolean,
        session: Session,
        offset: Int = 0,
        timeoutPerSiteMs: Long = 9_000L
    ): Scan {
        val eligible = sites.filter { (it.spiderApi != null || it.scriptApi != null) && allowed(it) }
            .distinctBy { it.key }.take(100)
        if (eligible.isEmpty()) return Scan(null, 0, 0, 0, 0)
        val start = offset.coerceIn(0, eligible.lastIndex)
        val page = eligible.drop(start).take(PAGE_SIZE)
        var checked = 0
        var episodesFound = 0
        val good = mutableListOf<Match>()
        for (site in page) {
            checked++
            val found = try {
                withTimeoutOrNull(timeoutPerSiteMs.coerceIn(1_000, 20_000)) {
                    if (!session.activate(site)) return@withTimeoutOrNull null
                    val options = session.search(site, identity)
                        .filter { it.sourceType == HerrinSourceType.TVBOX_SPIDER }
                        .sortedByDescending { it.resolutionClaim ?: 0 }.take(3)
                    for (offer in options) {
                        val film = session.details(offer) ?: continue
                        if (HerrinPlaybackEntry.firstPlayable(film) == null) continue
                        episodesFound++
                        // Spider playerContent must yield an actual authorized
                        // media route; broken PROXY_INVALID_ROUTE is rejected.
                        if (!session.canPlay(film)) continue
                        val labels = film.playbackEpisodes.flatMap { ep ->
                            ep.lines.map { it.title }
                        }.take(30).joinToString(" ")
                        val quality = maxOf(
                            offer.resolutionClaim ?: 0,
                            HerrinFilmIdentityMatch.resolutionClaim(labels) ?: 0)
                        return@withTimeoutOrNull Match(site, film, quality, offer)
                    }
                    null
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
            if (found != null) good += found
        }
        val ranked = good.sortedWith(compareByDescending<Match> { it.claim }
            .thenByDescending { it.detailed.playbackEpisodes.size })
        // Every activate(site) may replace the active Spider and its loopback
        // proxy session. Re-validate the winning candidate AFTER restoring
        // its site, never hand the UI a media route verified in an old session.
        // If the best site expired during the scan, try the next verified site.
        var restored: Match? = null
        for (winner in ranked) {
            val current = try {
                withTimeoutOrNull(timeoutPerSiteMs.coerceIn(1_000, 20_000)) {
                    if (!session.activate(winner.site)) return@withTimeoutOrNull null
                    val refreshed = session.details(winner.candidate)
                        ?: return@withTimeoutOrNull null
                    if (HerrinPlaybackEntry.firstPlayable(refreshed) == null ||
                        !session.canPlay(refreshed)) return@withTimeoutOrNull null
                    winner.copy(detailed = refreshed)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
            if (current != null) { restored = current; break }
        }
        return Scan(restored, checked, eligible.size,
            if (start + page.size >= eligible.size) 0 else start + page.size,
            episodesFound)
    }
}
