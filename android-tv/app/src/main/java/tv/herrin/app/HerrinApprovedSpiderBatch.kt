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
        suspend fun eligible(site: TvBoxSite): Boolean = true
        suspend fun activate(site: TvBoxSite): Boolean
        suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity): List<HerrinSourceMovie>
        suspend fun details(candidate: HerrinSourceMovie): Film?
        suspend fun canPlay(detailed: Film): Boolean
        // Production returns the episode/line that survived playerContent AND
        // bounded real-byte validation. JVM fake sessions use the legacy default.
        suspend fun pick(detailed: Film): Pair<Int, String>? =
            if (canPlay(detailed)) HerrinPlaybackEntry.firstPlayable(detailed) else null
    }

    data class Match(val site: TvBoxSite, val detailed: Film, val claim: Int,
                     val candidate: HerrinSourceMovie,
                     val episode: Int, val line: String)
    enum class Outcome { VERIFIED, TIMEOUT, ACTIVATE_FAILED, NO_MATCH, NO_EPISODES, PARSE_FAILED, INVALID_MEDIA, ERROR, DEFERRED }
    data class Scan(val match: Match?, val checked: Int, val eligible: Int,
                    val nextOffset: Int, val candidatesWithEpisodes: Int,
                    val lastOutcome: Outcome? = null, val activeIndex: Int? = null,
                    val stage: SourceSearchStage? = null, val elapsedMs: Long = 0)

    /**
     * One tap scans successive bounded pages of the already approved JAR
     * without requiring the user to pre-select a particular node. A single
     * Spider worker cannot safely run different sites concurrently, so the
     * search uses a wall-clock budget and publishes the best confirmed match.
     * Later pages may outrank the first page, but every returned winner is
     * re-activated and re-verified under its CURRENT proxy session.
     */
    // A restored Spider session may return different episode/line labels.
    // Never keep a stale 4K claim after the actual playable line changes.
    private fun selectedLineClaim(
        film: Film, chosen: Pair<Int, String>, fallback: Int = 0
    ): Int {
        val label = film.playbackEpisodes.firstOrNull { it.number == chosen.first }
            ?.lines?.firstOrNull { it.id == chosen.second }?.title
        return label?.let(HerrinFilmIdentityMatch::resolutionClaim) ?: fallback
    }

    /**
     * A Spider site can change its playable line after re-activation. Rank
     * candidates by the line verified in the CURRENT session, not the stale
     * quality observed before another site's worker replaced the session.
     *
     * Stop as soon as the current verified line outranks every previously
     * refreshed and still-unchecked candidate. Only extra-activate sites
     * when a formerly better site has actually downgraded or expired.
     */
    private suspend fun restoreVerifiedBest(
        winners: List<Match>, session: Session, timeoutPerSiteMs: Long
    ): Match? {
        val ranked = winners.sortedWith(compareByDescending<Match> { it.claim }
            .thenByDescending { it.detailed.playbackEpisodes.size })

        suspend fun refresh(winner: Match): Match? = try {
            withTimeoutOrNull(timeoutPerSiteMs) {
                if (!session.activate(winner.site)) return@withTimeoutOrNull null
                val detail = session.details(winner.candidate)
                    ?: return@withTimeoutOrNull null
                val chosen = session.pick(detail) ?: return@withTimeoutOrNull null
                winner.copy(detailed = detail,
                    claim = selectedLineClaim(detail, chosen),
                    episode = chosen.first, line = chosen.second)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { null }

        val refreshed = mutableListOf<Match>()
        for ((index, winner) in ranked.withIndex()) {
            val current = refresh(winner) ?: continue
            val earlierHigher = refreshed.any { it.claim > current.claim }
            refreshed += current
            val remainingHigher = ranked.drop(index + 1).any { it.claim > current.claim }
            if (!earlierHigher && !remainingHigher) return current
        }
        // The last probed site owns the single active Spider session. Any
        // previously checked fallback must be reactivated before returning.
        for (winner in refreshed.sortedByDescending { it.claim }) {
            refresh(winner)?.let { return it }
        }
        return null
    }

    suspend fun scanAcrossPages(
        identity: HerrinFilmIdentity,
        sites: List<TvBoxSite>,
        allowed: (TvBoxSite) -> Boolean,
        session: Session,
        offset: Int = 0,
        maxPages: Int = 17,
        maxWallClockMs: Long = 75_000L,
        stopAtFirstPlayable: Boolean = false,
        onProgress: suspend (Scan) -> Unit = {},
        onSiteProgress: suspend (Scan) -> Unit = {},
        onEvent: suspend (SourceSearchEvent) -> Unit = {},
        timeoutPerSiteMs: Long = 3_500L,
        stageBudgetMs: Long = 1_200L
    ): Scan {
        val winners = mutableListOf<Match>()
        var checked = 0
        var total = 0
        var episodes = 0
        var next = offset
        // The old 30-second budget could expire after just six sites, leaving
        // 39/45 unchecked and requiring repeated remote-control presses.
        // Continue across finite six-site pages, still using each site's
        // 4.5-second bound and a total wall-clock deadline.
        kotlinx.coroutines.withTimeoutOrNull(maxWallClockMs.coerceIn(1L, 300_000L)) {
            repeat(maxPages.coerceIn(1, 17)) {
                val completedBeforePage = checked
                val episodesBeforePage = episodes
                val offsetBeforePage = next
                val page = scan(identity, sites, allowed, session, offset = next,
                    timeoutPerSiteMs = timeoutPerSiteMs,
                    stopAtFirstPlayable = stopAtFirstPlayable,
                    onEvent = onEvent, stageBudgetMs = stageBudgetMs,
                    onSiteProgress = { partial ->
                        // Preserve the next unchecked site even on mid-page timeout.
                        checked = completedBeforePage + partial.checked
                        total = partial.eligible
                        episodes = episodesBeforePage + partial.candidatesWithEpisodes
                        next = partial.nextOffset
                        onSiteProgress(Scan(partial.match,
                            checked, total, next, episodes, partial.lastOutcome,
                            partial.activeIndex,partial.stage,partial.elapsedMs))
                    })
                checked = completedBeforePage + page.checked
                total = page.eligible
                episodes = episodesBeforePage + page.candidatesWithEpisodes
                page.match?.let(winners::add)
                next = page.nextOffset
                onProgress(Scan(page.match, checked, total, next, episodes))
                if (stopAtFirstPlayable && page.match != null)
                    return@withTimeoutOrNull
                if (next == 0 || next == offsetBeforePage || winners.any { it.claim >= 2160 })
                    return@withTimeoutOrNull
            }
        }
        // scan() already reactivated and verified its returned winner. Do not
        // re-scan other sites just to chase 4K when first playback matters.
        val result = if (stopAtFirstPlayable) winners.firstOrNull()
            else restoreVerifiedBest(winners, session, 5_000L)
        return Scan(result, checked, total, next, episodes)
    }

    suspend fun scan(
        identity: HerrinFilmIdentity,
        sites: List<TvBoxSite>,
        allowed: (TvBoxSite) -> Boolean,
        session: Session,
        offset: Int = 0,
        timeoutPerSiteMs: Long = 9_000L,
        stopAtFirstPlayable: Boolean = false,
        onSiteProgress: suspend (Scan) -> Unit = {},
        onEvent: suspend (SourceSearchEvent) -> Unit = {},
        stageBudgetMs: Long = 1_200L
    ): Scan {
        val eligible = sites.filter { (it.spiderApi != null || it.scriptApi != null) && allowed(it) }
            .distinctBy { it.key }.take(100)
        if (eligible.isEmpty()) return Scan(null, 0, 0, 0, 0)
        val start = offset.coerceIn(0, eligible.lastIndex)
        val page = eligible.drop(start).take(PAGE_SIZE)
        var checked = 0
        var episodesFound = 0
        var processed = 0
        val good = mutableListOf<Match>()
        for (site in page) {
            if(!session.eligible(site)){
                processed++
                onSiteProgress(Scan(null,checked,eligible.size,if(start+processed>=eligible.size)0 else start+processed,
                    episodesFound,Outcome.DEFERRED))
                continue
            }
            val started = System.nanoTime()
            var stage = SourceSearchStage.ACTIVATE
            suspend fun publishActive() = onSiteProgress(Scan(null,checked,eligible.size,
                start+processed,episodesFound,activeIndex=start+processed+1,stage=stage,
                elapsedMs=(System.nanoTime()-started)/1_000_000))
            suspend fun <T> phase(value: SourceSearchStage, block: suspend () -> T): T {
                stage=value;publishActive()
                onEvent(SourceSearchEvent("spider:"+site.key,value,SourceSearchOutcome.CHECKING))
                val at=System.nanoTime()
                data class Completed<T>(val value: T)
                val result=if(stopAtFirstPlayable) withTimeoutOrNull(stageBudgetMs.coerceAtLeast(1)) {
                    kotlinx.coroutines.withContext(SpiderRequestBudget(stageBudgetMs)) { Completed(block()) }
                } else Completed(block())
                if(result==null)throw NodeDeadline()
                val ok=result.value!=null &&result.value!=false &&(result.value !is List<*> || (result.value as List<*>).isNotEmpty())
                val code=if(ok)SourceSearchOutcome.VERIFIED else when(value){
                    SourceSearchStage.ACTIVATE->SourceSearchOutcome.ACTIVATE_FAILED
                    SourceSearchStage.SEARCH->SourceSearchOutcome.NO_MATCH
                    SourceSearchStage.DETAILS->SourceSearchOutcome.NO_EPISODES
                    SourceSearchStage.PARSE->SourceSearchOutcome.PARSE_FAILED
                    else->SourceSearchOutcome.MEDIA_FAILED
                }
                onEvent(SourceSearchEvent("spider:"+site.key,value,code,
                    (System.nanoTime()-at)/1_000_000))
                return result.value
            }
            var finished = false
            var failed = false
            var outcome = Outcome.ACTIVATE_FAILED
            val found = try {
                withTimeoutOrNull(timeoutPerSiteMs.coerceIn(1_000, 20_000)) {
                    if (!phase(SourceSearchStage.ACTIVATE) { session.activate(site) }) {
                        finished = true
                        return@withTimeoutOrNull null
                    }
                    outcome = Outcome.NO_MATCH
                    val options = phase(SourceSearchStage.SEARCH) { session.search(site, identity) }
                        .filter { it.sourceType == HerrinSourceType.TVBOX_SPIDER &&
                            it.sourceId == "spider:"+site.key && HerrinFilmIdentityMatch.matches(identity,it) }
                        .sortedByDescending { it.resolutionClaim ?: 0 }.take(3)
                    if (options.isEmpty()) {
                        finished = true
                        return@withTimeoutOrNull null
                    }
                    outcome = Outcome.NO_EPISODES
                    // Search rank is only a catalog claim. Examine all at
                    // most three offers from this approved site and compare
                    // the line actually accepted by playerContent/media probe.
                    // Keep the per-site timeout and restore the eventual
                    // winner under the current Spider session below.
                    var best: Match? = null
                    for (offer in options) {
                        val film = phase(SourceSearchStage.DETAILS) { session.details(offer) } ?: continue
                        if (!HerrinFilmIdentityMatch.matches(identity,offer.copy(film=film))) continue
                        if (HerrinPlaybackEntry.firstPlayable(film) == null) continue
                        episodesFound++
                        // Spider playerContent must yield an actual authorized
                        // media route; broken PROXY_INVALID_ROUTE is rejected.
                        outcome = Outcome.INVALID_MEDIA
                        val chosen = phase(SourceSearchStage.MEDIA) { session.pick(film) } ?: continue
                        // Do not let an advertised 4K filename outrank an
                        // actually validated 1080p episode/line.
                        val quality = selectedLineClaim(film, chosen, offer.resolutionClaim ?: 0)
                        if (quality > (best?.claim ?: -1)) {
                            best = Match(site, film, quality, offer, chosen.first, chosen.second)
                        }
                        if (stopAtFirstPlayable && best != null) break
                    }
                    finished = true
                    best
                }
            } catch (_: NodeDeadline) { null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { failed = true; null }
            if (found != null) good += found
            checked++
            processed++
            val lastOutcome = when {
                found != null -> Outcome.VERIFIED
                failed -> Outcome.ERROR
                !finished -> Outcome.TIMEOUT
                else -> outcome
            }
            val nextUnchecked = if (start + processed >= eligible.size) 0 else start + processed
            val code=when(lastOutcome){
                Outcome.VERIFIED->SourceSearchOutcome.VERIFIED
                Outcome.TIMEOUT->SourceSearchOutcome.TIMEOUT
                Outcome.ACTIVATE_FAILED->SourceSearchOutcome.ACTIVATE_FAILED
                Outcome.NO_MATCH->SourceSearchOutcome.NO_MATCH
                Outcome.NO_EPISODES->SourceSearchOutcome.NO_EPISODES
                Outcome.PARSE_FAILED->SourceSearchOutcome.PARSE_FAILED
                Outcome.INVALID_MEDIA->SourceSearchOutcome.MEDIA_FAILED
                Outcome.ERROR->SourceSearchOutcome.ERROR
                Outcome.DEFERRED->SourceSearchOutcome.RATE_LIMITED
            }
            onEvent(SourceSearchEvent("spider:"+site.key,stage,code,(System.nanoTime()-started)/1_000_000,
                checked,eligible.size))
            // No URLs, credentials or raw provider exceptions in UI diagnostics.
            onSiteProgress(Scan(found, checked, eligible.size, nextUnchecked, episodesFound, lastOutcome,
                stage=stage,elapsedMs=(System.nanoTime()-started)/1_000_000))
            if (stopAtFirstPlayable && found != null) {
                // playerContent + bounded real media probe already succeeded
                // in the currently active Spider session.
                return Scan(found, checked, eligible.size, nextUnchecked, episodesFound)
            }
        }
        // Multiple site activations invalidate the previous live proxy.
        // Revalidate candidates and compare their CURRENT selected line.
        val restored = restoreVerifiedBest(good, session,
            timeoutPerSiteMs.coerceIn(1_000, 20_000))
        return Scan(restored, checked, eligible.size,
            if (start + page.size >= eligible.size) 0 else start + page.size,
            episodesFound)
    }
    private class NodeDeadline : Exception()
}
