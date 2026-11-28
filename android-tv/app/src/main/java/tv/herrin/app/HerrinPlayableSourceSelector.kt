package tv.herrin.app

import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * End-to-end source gate for a SINGLE film on a SINGLE authorized provider.
 * It tries explicit episode/line choices, runs actual provider playerContent,
 * then reads bounded real media bytes with the same PlaybackMedia auth headers.
 *
 * The returned episode + line MUST be given to the UI; the first listed option
 * may be a dead 4K route while a verified second line plays. Does not cross
 * movie identities, authorization scopes or unapproved Spider sessions.
 */
internal object HerrinPlayableSourceSelector {
    data class Selected(val episode: Int, val line: String, val media: PlaybackMedia,
                        val claimedResolution: Int, val mediaBytesVerified: Boolean = true)

    private fun origin(value: String): Triple<String, String, Int>? = try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in listOf("http", "https") || host.isNullOrBlank()) null
        else Triple(scheme!!, host, if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80)
    } catch (_: Exception) { null }

    suspend fun find(
        film: Film,
        resolve: suspend (Int, String) -> PlaybackResult,
        verifyMedia: suspend (PlaybackMedia) -> Boolean,
        limit: Int = 6,
        coordinate: HerrinPlaybackCoordinate? = null,
        onStage: suspend (SourceSearchStage) -> Unit = {},
        diagnose: Boolean = false
    ): Selected? {
        val attempts = film.playbackEpisodes.flatMapIndexed { index, episode ->
            episode.lines.filter { it.uri.isNotBlank() && coordinate?.accepts(episode, it) != false }.map { line ->
                Triple(index, episode, line)
            }
        }.sortedWith(compareBy<Triple<Int, PlaybackEpisode, PlaybackLine>> { it.first }
            .thenByDescending { HerrinFilmIdentityMatch.resolutionClaim(it.third.title) ?: 0 })
            .take(limit.coerceIn(1, 12))
        var failure=SourceCandidateFailure(SourceSearchStage.DETAILS,SourceSearchOutcome.NO_EPISODES)
        for ((_, episode, line) in attempts) {
            onStage(SourceSearchStage.PARSE)
            val ready = try { resolve(episode.number, line.id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error:SourceHttpFailure) { if(error.code in setOf(401,403,429))throw error else null }
            catch (_: Exception) { null }
            val media = (ready as? PlaybackResult.Ready)?.media ?: run {
                failure=SourceCandidateFailure(SourceSearchStage.PARSE,SourceSearchOutcome.PARSE_FAILED)
                null
            }
            if(media==null)continue
            if (media.kernelPlayback != null) {
                // Selection admission only. Original kernel validates media
                // when its player starts; never call this MEDIA_BYTES_VERIFIED.
                return Selected(episode.number, line.id, media, 0, mediaBytesVerified = false)
            }
            // A provider can return a dead primary and working alternate. Verify each
            // candidate before handing it to Media3; never retain unverified fallbacks.
            // Authenticated media must not forward credentials to a different origin.
            val hasSensitiveHeaders = media.headers.keys.any {
                it.equals("Authorization", true) || it.equals("Cookie", true) ||
                    it.equals("Proxy-Authorization", true)
            }
            val primaryOrigin = origin(media.uri)
            val candidates = (listOf(media.uri) + media.alternateUris)
                .distinct().take(6)
            for (candidate in candidates) {
                if (candidate != media.uri && hasSensitiveHeaders &&
                    (primaryOrigin == null || origin(candidate) != primaryOrigin)) continue
                val single = media.copy(uri = candidate, alternateUris = emptyList())
                onStage(SourceSearchStage.MEDIA)
                val verified = try { verifyMedia(single) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error:SourceHttpFailure) { if(error.code in setOf(401,403,429))throw error else {
                    failure=SourceCandidateFailure(SourceSearchStage.MEDIA,SourceHealthIndex.httpOutcome(error.code));false
                } }
                catch (_: Exception) { false }
                if (verified) return Selected(
                    episode.number, line.id, single,
                    HerrinFilmIdentityMatch.resolutionClaim(line.title) ?: 0)
                if(failure.stage!=SourceSearchStage.MEDIA)failure=SourceCandidateFailure(SourceSearchStage.MEDIA,SourceSearchOutcome.MEDIA_FAILED)
            }
        }
        if(diagnose)throw failure
        return null
    }
}
