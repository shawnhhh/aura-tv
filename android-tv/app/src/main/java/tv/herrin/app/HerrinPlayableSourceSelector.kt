package tv.herrin.app

import kotlinx.coroutines.CancellationException

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
                        val claimedResolution: Int)

    suspend fun find(
        film: Film,
        resolve: suspend (Int, String) -> PlaybackResult,
        verifyMedia: suspend (PlaybackMedia) -> Boolean,
        limit: Int = 6
    ): Selected? {
        val attempts = film.playbackEpisodes.flatMapIndexed { index, episode ->
            episode.lines.filter { it.uri.isNotBlank() }.map { line ->
                Triple(index, episode, line)
            }
        }.sortedWith(compareBy<Triple<Int, PlaybackEpisode, PlaybackLine>> { it.first }
            .thenByDescending { HerrinFilmIdentityMatch.resolutionClaim(it.third.title) ?: 0 })
            .take(limit.coerceIn(1, 12))
        for ((_, episode, line) in attempts) {
            val ready = try { resolve(episode.number, line.id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            val media = (ready as? PlaybackResult.Ready)?.media ?: continue
            val verified = try { verifyMedia(media) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
            if (verified) return Selected(
                episode.number, line.id, media,
                HerrinFilmIdentityMatch.resolutionClaim(line.title) ?: 0)
        }
        return null
    }
}
