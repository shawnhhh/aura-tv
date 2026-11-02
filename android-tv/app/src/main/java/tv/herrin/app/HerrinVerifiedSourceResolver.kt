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
        remoteDetails: suspend (HerrinSourceMovie) -> Film?
    ): HerrinVerifiedSource {
        var personalPlaylist: HerrinPersonalPlaylist? = null
        var detailedFilm: Film? = null
        val result = HerrinFilmRouter(adapters).find(identity) { ranked ->
            if (ranked.firstOrNull()?.tier == HerrinSourceTier.PERSONAL) {
                // Use exactly the same source-specific permission check as
                // actual playback. Never accept an unauthorized WebDAV file.
                val allowed = ranked.filter { personalPlayback(it) is PlaybackResult.Ready }
                val built = HerrinPersonalPlaylistBuilder.create(allowed, personalPlayback)
                if (built.episodes.isEmpty()) emptyList()
                else {
                    personalPlaylist = built
                    allowed
                }
            } else {
                // Read actual CMS/Spider episodes. Different same-name films
                // can fail independently; do not stop after the first error.
                val playable = ranked.take(8).firstOrNull { candidate ->
                    val detailed = try { remoteDetails(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (detailed?.playbackEpisodes?.any { it.lines.isNotEmpty() } == true) {
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
