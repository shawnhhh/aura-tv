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
        remoteDetails: suspend (HerrinSourceMovie) -> Film?
    ): HerrinVerifiedSource {
        var personalPlaylist: HerrinPersonalPlaylist? = null
        var detailedFilm: Film? = null
        var verifiedLine: Pair<Int, String>? = null
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
                // At most eight one-byte requests, but stop at the FIRST
                // verified file. Probing all 8 after an immediate success
                // added up to 20 seconds of unnecessary TV remote latency.
                // Keep later season episodes selectable without eagerly
                // downloading or probing every episode. Media3 will validate
                // each chosen file during real playback.
                val knownUnavailable = mutableSetOf<HerrinSourceMovie>()
                var verifiedOne = false
                for ((candidate, media) in prepared.take(8)) {
                    val valid = try {
                        kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                            verifyPersonal(media)
                        } == true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    if (valid) {
                        verifiedOne = true
                        break
                    }
                    knownUnavailable += candidate
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
                    if (built.episodes.isEmpty()) emptyList()
                    else {
                        personalPlaylist = built
                        accepted.map { it.first }
                    }
                }
            } else {
                // Read actual CMS/Spider episodes. Different same-name films
                // can fail independently; do not stop after the first error.
                val playable = fairRemoteCandidates(ranked).firstOrNull { candidate ->
                    val detailed = try { remoteDetails(candidate) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (detailed != null && HerrinPlaybackEntry.firstPlayable(detailed) != null) {
                        val verified = try { verifyRemote(candidate, detailed) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        val choice = if (verified) selectRemote(candidate, detailed)
                            else null
                        if (choice != null) {
                            detailedFilm = detailed
                            verifiedLine = choice
                        }
                        choice != null
                    } else false
                }
                listOfNotNull(playable)
            }
        }
        return HerrinVerifiedSource(result, personalPlaylist, detailedFilm, verifiedLine)
    }
}
