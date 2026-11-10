package tv.herrin.app

/**
 * A metadata title is not playable until we can create an authorized media
 * request (personal) or load real, non-empty episodes (CMS/Spider).
 * Exhaust unusable NAS/cloud matches before trying the TVBox tier; never
 * blindly choose a matching title with no usable playback mapping.
 */
internal data class HerrinVerifiedSource(
    val lookup: HerrinFilmLookupResult,
    val personalPlaylist: HerrinPersonalPlaylist? = null,
    val sourceDetails: Film? = null,
    val verifiedLine: Pair<Int, String>? = null
)

internal class HerrinVerifiedSourceResolver(private val adapters: List<HerrinFilmSource>) {
    /**
     * The catalog is sorted by untrusted quality labels. The first eight hits
     * may all come from a single broken 4K CMS node, starving every other
     * authorized provider. Give each source its first matching title before
     * checking additional titles from the same source. Keep bounded budget.
     */
    internal fun fairRemoteCandidates(
        ranked: List<HerrinSourceMovie>, budget: Int = 8
    ): List<HerrinSourceMovie> {
        val firstBySource = ranked.distinctBy { it.sourceId }
        val firstItems = firstBySource.toHashSet()
        return (firstBySource + ranked.filterNot { it in firstItems })
            .take(budget.coerceIn(1, 16))
    }

    suspend fun find(
        identity: HerrinFilmIdentity,
        personalPlayback: (HerrinSourceMovie) -> PlaybackResult,
        verifyPersonal: suspend (PlaybackMedia) -> Boolean = { true },
        verifyRemote: suspend (HerrinSourceMovie, Film) -> Boolean = { _, _ -> true },
        selectRemote: suspend (HerrinSourceMovie, Film) -> Pair<Int, String>? =
            { _, film -> HerrinPlaybackEntry.firstPlayable(film) },
        coordinate: HerrinPlaybackCoordinate? = null,
        streaming: Boolean = false,
        preferredSourceId: String? = null,
        health: SourceHealthIndex? = null,
        onEvent: suspend (SourceSearchEvent) -> Unit = {},
        remoteDetails: suspend (HerrinSourceMovie) -> Film?
    ): HerrinVerifiedSource {
        var personalPlaylist: HerrinPersonalPlaylist? = null
        var detailedFilm: Film? = null
        var verifiedLine: Pair<Int, String>? = null
        val result = HerrinFilmRouter(adapters, preferredSourceId).find(identity,streaming,health,onEvent) { ranked ->
            if (ranked.firstOrNull()?.tier == HerrinSourceTier.PERSONAL) {
                // Use exactly the same source-specific permission check as
                // actual playback. Never accept an unauthorized WebDAV file.
                val prepared = ranked.take(80).mapNotNull { candidate ->
                    val result = try { personalPlayback(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    (result as? PlaybackResult.Ready)?.media?.let { candidate to it }
                }
                // At most eight one-byte requests, but stop at the FIRST
                // verified file. Probing all 8 after an immediate success
                // added up to 20 seconds of unnecessary TV remote latency.
                // Keep later season episodes selectable without eagerly
                // downloading or probing every episode. Media3 will validate
                // each chosen file during real playback.
                val knownUnavailable = mutableSetOf<HerrinSourceMovie>()
                var verifiedOne = false
                var verifiedMedia:PlaybackMedia?=null
                for ((candidate, media) in prepared.take(8)) {
                    var terminalHttp:SourceHttpFailure?=null
                    onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.MEDIA,SourceSearchOutcome.CHECKING))
                    val began=System.nanoTime()
                    val valid = try {
                        kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                            verifyPersonal(media)
                        } == true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch(error:SourceHttpFailure){
                        terminalHttp=error
                        val outcome=SourceHealthIndex.httpOutcome(error.code)
                        health?.record(candidate.sourceId,SourceSearchStage.MEDIA,outcome,(System.nanoTime()-began)/1_000_000,error.retryAfterMs)
                        onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.MEDIA,outcome,retryAfterMs=error.retryAfterMs))
                        false
                    } catch (_: Exception) { false }
                    if(terminalHttp?.code in setOf(401,403,429))break
                    if (valid) {
                        health?.record(candidate.sourceId,SourceSearchStage.MEDIA,SourceSearchOutcome.VERIFIED,(System.nanoTime()-began)/1_000_000)
                        onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.MEDIA,SourceSearchOutcome.VERIFIED,(System.nanoTime()-began)/1_000_000))
                        verifiedOne = true
                        verifiedMedia=media
                        break
                    }
                    knownUnavailable += candidate
                    health?.record(candidate.sourceId,SourceSearchStage.MEDIA,SourceSearchOutcome.MEDIA_FAILED,(System.nanoTime()-began)/1_000_000)
                }
                if (!verifiedOne) emptyList()
                else {
                    val accepted = prepared.filterNot { it.first in knownUnavailable }
                    val readyByOffer = accepted.toMap()
                    val built = HerrinPersonalPlaylistBuilder.create(
                        accepted.map { it.first }) { candidate ->
                        readyByOffer[candidate]?.let { PlaybackResult.Ready(it) }
                            ?: PlaybackResult.Unavailable("个人片源已失效")
                    }
                    val chosen = built.episodes.firstNotNullOfOrNull { ep ->
                        ep.lines.firstOrNull { coordinate?.accepts(ep, it) != false &&
                            built.mediaByLine[it.id]?.let{candidate->candidate.uri==verifiedMedia?.uri &&
                                candidate.headers==verifiedMedia?.headers &&candidate.allowHttpRedirects==verifiedMedia?.allowHttpRedirects}==true }?.let { ep.number to it.id }
                    }
                    if (chosen == null) emptyList()
                    else {
                        personalPlaylist = built
                        verifiedLine = chosen
                        accepted.map { it.first }
                    }
                }
            } else {
                // Read actual CMS/Spider episodes. Different same-name films
                // can fail independently; do not stop after the first error.
                val blockedSources=mutableSetOf<String>()
                val playable = fairRemoteCandidates(ranked).firstOrNull { candidate ->
                    if(candidate.sourceId in blockedSources)return@firstOrNull false
                    onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.DETAILS,SourceSearchOutcome.CHECKING))
                    val detailed = try { remoteDetails(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch(error:SourceHttpFailure){
                        if(error.code in setOf(401,403,429))blockedSources+=candidate.sourceId
                        val outcome=SourceHealthIndex.httpOutcome(error.code)
                        health?.record(candidate.sourceId,SourceSearchStage.DETAILS,outcome,0,error.retryAfterMs)
                        onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.DETAILS,outcome,retryAfterMs=error.retryAfterMs))
                        return@firstOrNull false
                    }
                    catch (_: Exception) { null }
                    if (detailed != null && HerrinFilmIdentityMatch.matches(identity,candidate.copy(film=detailed)) && HerrinPlaybackEntry.firstPlayable(detailed) != null) {
                        val began=System.nanoTime()
                        onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.MEDIA,SourceSearchOutcome.CHECKING))
                        var terminalHttp:SourceHttpFailure?=null
                        var validationFailure:SourceCandidateFailure?=null
                        val verified = try { verifyRemote(candidate, detailed) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch(error:SourceHttpFailure){terminalHttp=error;false}
                        catch(error:SourceCandidateFailure){validationFailure=error;false}
                        catch (_: Exception) { false }
                        val choice = if (verified) selectRemote(candidate, detailed)
                            else null
                        if (choice != null) {
                            detailedFilm = detailed
                            verifiedLine = choice
                        }
                        val result=terminalHttp?.let{SourceHealthIndex.httpOutcome(it.code)}?:validationFailure?.outcome?:if(choice!=null)SourceSearchOutcome.VERIFIED else SourceSearchOutcome.MEDIA_FAILED
                        if(terminalHttp?.code in setOf(401,403,429))blockedSources+=candidate.sourceId
                        val phase=validationFailure?.stage?:SourceSearchStage.MEDIA
                        health?.record(candidate.sourceId,phase,result,(System.nanoTime()-began)/1_000_000,terminalHttp?.retryAfterMs)
                        onEvent(SourceSearchEvent(candidate.sourceId,phase,result,(System.nanoTime()-began)/1_000_000,retryAfterMs=terminalHttp?.retryAfterMs))
                        choice != null
                    } else {
                        onEvent(SourceSearchEvent(candidate.sourceId,SourceSearchStage.DETAILS,
                            if(detailed!=null &&!HerrinFilmIdentityMatch.matches(identity,candidate.copy(film=detailed)))SourceSearchOutcome.NO_MATCH else SourceSearchOutcome.NO_EPISODES))
                        false
                    }
                }
                listOfNotNull(playable)
            }
        }
        return HerrinVerifiedSource(result, personalPlaylist, detailedFilm, verifiedLine)
    }
}
