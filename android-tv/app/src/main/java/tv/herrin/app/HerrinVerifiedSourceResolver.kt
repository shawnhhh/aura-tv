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
    val sourceDetails: Film? = null
)

internal class HerrinVerifiedSourceResolver(private val adapters: List<HerrinFilmSource>) {
    suspend fun find(
        identity: HerrinFilmIdentity,
        personalPlayback: (HerrinSourceMovie) -> PlaybackResult,
        verifyPersonal: suspend (PlaybackMedia) -> Boolean = { true },
        remoteDetails: suspend (HerrinSourceMovie) -> Film?
    ): HerrinVerifiedSource {
        var personalPlaylist: HerrinPersonalPlaylist? = null
        var detailedFilm: Film? = null
        val result = HerrinFilmRouter(adapters).find(identity) { ranked ->
            if (ranked.firstOrNull()?.tier == HerrinSourceTier.PERSONAL) {
                // Use exactly the same source-specific permission check as
                // actual playback. Never accept an unauthorized WebDAV file.
                val prepared = ranked.take(80).mapNotNull { candidate ->
                    val result = try { personalPlayback(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    (result as? PlaybackResult.Ready)?.media?.let { candidate to it }
                }
                // Probe the highest-ranked media within an eight-request budget.
                // If every attempted URL is inaccessible, advance to the next
                // tier instead of advertising a non-playable NAS/cloud match.
                // Once one real file responds, keep unprobed series episodes
                // selectable: each later episode is still checked by Media3.
                val probed = mutableListOf<Pair<HerrinSourceMovie, PlaybackMedia>>()
                val knownUnavailable = mutableSetOf<HerrinSourceMovie>()
                for ((candidate, media) in prepared.take(8)) {
                    val valid = kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                        verifyPersonal(media)
                    } == true
                    if (valid) probed += candidate to media
                    else knownUnavailable += candidate
                }
                if (probed.isEmpty()) emptyList()
                else {
                    val accepted = (probed + prepared.drop(8))
                        .filterNot { it.first in knownUnavailable }
                    val readyByOffer = accepted.toMap()
                    val built = HerrinPersonalPlaylistBuilder.create(
                        accepted.map { it.first }) { candidate ->
                        readyByOffer[candidate]?.let { PlaybackResult.Ready(it) }
                            ?: PlaybackResult.Unavailable("个人片源已失效")
                    }
                    if (built.episodes.isEmpty()) emptyList()
                    else {
                        personalPlaylist = built
                        accepted.map { it.first }
                    }
                }
            } else {
                // Read actual CMS/Spider episodes. Different same-name films
                // can fail independently; do not stop after the first error.
                val playable = ranked.take(8).firstOrNull { candidate ->
                    val detailed = try { remoteDetails(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (HerrinPlaybackEntry.firstPlayable(detailed) != null) {
                        detailedFilm = detailed
                        true
                    } else false
                }
                listOfNotNull(playable)
            }
        }
        return HerrinVerifiedSource(result, personalPlaylist, detailedFilm)
    }
}
