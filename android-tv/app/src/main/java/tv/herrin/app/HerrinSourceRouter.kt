package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * TMDB owns discovery and display. These adapters own playback *availability*.
 * The UI must never infer a video URL or playable episode from TMDB metadata.
 */
internal enum class HerrinSourceTier { PERSONAL, TVBOX }
internal enum class HerrinSourceType { NAS, CLOUD, TVBOX_CMS, TVBOX_SPIDER }

internal data class HerrinFilmIdentity(
    val tmdbId: Int,
    val mediaType: String,
    val names: List<String>,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
) {
    init {
        require(tmdbId > 0 && mediaType in setOf("movie", "tv"))
        require(names.any { it.isNotBlank() })
    }
}

internal data class HerrinSourceMovie(
    val sourceId: String,
    val sourceType: HerrinSourceType,
    val film: Film,
    val mediaType: String,
    val titleAliases: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    /** From filename/catalog labels only; not proof of a decoded 4K/HDR frame. */
    val resolutionClaim: Int? = null,
    val hdrClaim: Boolean = false
) {
    val tier: HerrinSourceTier get() =
        if (sourceType in setOf(HerrinSourceType.NAS, HerrinSourceType.CLOUD))
            HerrinSourceTier.PERSONAL else HerrinSourceTier.TVBOX
}

internal interface HerrinFilmSource {
    val id: String
    val type: HerrinSourceType
    /** Bounded provider search; no implicit execution of unapproved JAR/scripts. */
    suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie>
}

internal data class HerrinFilmLookupResult(
    val options: List<HerrinSourceMovie>,
    val selectedTier: HerrinSourceTier?,
    val errors: Map<String, String>,
    val searchedSources: List<String>
)

/**
 * Strict source priority: NAS -> cloud WebDAV -> TVBox CMS/approved Spider.
 * A cloud "4K" filename cannot outrank a verified NAS 1080p resource.
 * Within a source tier, quality labels only break ties between matches.
 */
internal class HerrinFilmRouter(private val sources: List<HerrinFilmSource>) {
    /**
     * Verify actual resource usability before accepting a priority tier.
     * If every matched NAS/cloud offer is unusable, continue to TVBox.
     * For TVBox a verifier can try multiple candidates' details/episode lists.
     */
    suspend fun find(
        identity: HerrinFilmIdentity,
        accept: suspend (List<HerrinSourceMovie>) -> List<HerrinSourceMovie> = { it }
    ): HerrinFilmLookupResult {
        val errors = linkedMapOf<String, String>()
        val searched = mutableListOf<String>()
        val sourceStages = listOf(
            listOf(HerrinSourceType.NAS),
            listOf(HerrinSourceType.CLOUD),
            listOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER)
        )
        for (stage in sourceStages) {
            val tier = if (stage.first() == HerrinSourceType.TVBOX_CMS)
                HerrinSourceTier.TVBOX else HerrinSourceTier.PERSONAL
            val results = mutableListOf<HerrinSourceMovie>()
            val stageSources = sources.filter { it.type in stage }.distinctBy { it.id }.take(16)
            suspend fun fetch(source: HerrinFilmSource): Pair<List<HerrinSourceMovie>, String?> {
                return try {
                    val matches = withTimeoutOrNull(6_000L) { source.search(identity) }
                        ?: return emptyList<HerrinSourceMovie>() to "搜索超时"
                    matches.take(80).filter {
                        it.sourceId == source.id && it.sourceType == source.type &&
                            HerrinFilmIdentityMatch.matches(identity, it)
                    } to null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList<HerrinSourceMovie>() to "来源检索失败"
                }
            }
            // CMS is passive HTTP and safe to query in four-way bounded
            // fan-out. Previously 10 x 6-second timeouts could block TMDB
            // details for a full minute before Spider search even began.
            // An active Spider uses one mutable JAR worker; never run its
            // operations concurrently with another Spider site.
            val ordered = if (tier == HerrinSourceTier.TVBOX)
                stageSources.filter { it.type == HerrinSourceType.TVBOX_CMS } +
                    stageSources.filter { it.type == HerrinSourceType.TVBOX_SPIDER }
            else stageSources
            val cmsCount = ordered.count { it.type == HerrinSourceType.TVBOX_CMS }
            val parallel = if (tier == HerrinSourceTier.TVBOX) cmsCount else 0
            for ((index, group) in ordered.chunked(4).withIndex()) {
                val shouldParallelize = parallel > 0 &&
                    index * 4 < cmsCount &&
                    group.all { it.type == HerrinSourceType.TVBOX_CMS }
                val fetched = if (shouldParallelize) coroutineScope {
                    group.map { source ->
                        async(Dispatchers.IO) { fetch(source) }
                    }.awaitAll()
                } else group.map { fetch(it) }
                for ((i, source) in group.withIndex()) {
                    searched += source.id
                    val (offers, failure) = fetched[i]
                    results += offers
                    if (failure != null) errors[source.id] = failure
                }
            }
            if (results.isNotEmpty()) {
                val ranked = results.distinctBy {
                    it.sourceId to (it.film.cmsVodId ?: it.film.spiderVodId ?: it.film.id)
                }.sortedWith(compareByDescending<HerrinSourceMovie> {
                    it.resolutionClaim ?: 0
                }.thenByDescending { it.hdrClaim }.thenBy { it.sourceId })
                val accepted = accept(ranked).filter { it in ranked }
                if (accepted.isNotEmpty())
                    return HerrinFilmLookupResult(accepted, tier, errors, searched)
            }
        }
        return HerrinFilmLookupResult(emptyList(), null, errors, searched)
    }
}

internal object HerrinFilmIdentityMatch {
    fun matches(request: HerrinFilmIdentity, offer: HerrinSourceMovie): Boolean {
        if (request.mediaType != offer.mediaType) return false
        if (request.season != null && offer.season != null && request.season != offer.season) return false
        if (request.episode != null && offer.episode != null && request.episode != offer.episode) return false
        val requested = request.names.map(ArtworkTitle::normalize).filter(String::isNotBlank).toSet()
        val offered = (listOf(offer.film.title) + offer.titleAliases)
            .map(ArtworkTitle::normalize).filter(String::isNotBlank).toSet()
        if (requested.intersect(offered).isEmpty()) return false
        val requestedYear = request.year
        val sourceYear = offer.film.year.toIntOrNull()
        if (requestedYear != null && sourceYear != null &&
            kotlin.math.abs(requestedYear - sourceYear) > 1) return false
        return true
    }

    fun resolutionClaim(title: String): Int? {
        val value = title.lowercase(Locale.ROOT)
        return when {
            Regex("""(?<!\d)(?:2160p|4k|uhd)(?!\w)""").containsMatchIn(value) -> 2160
            Regex("""(?<!\d)(?:1080p|fhd)(?!\w)""").containsMatchIn(value) -> 1080
            Regex("""(?<!\d)720p(?!\w)""").containsMatchIn(value) -> 720
            else -> null
        }
    }
}

/** IPTV: M3U/direct playlist is authoritative; TVBox adds only missing options. */
internal object HerrinLivePriority {
    private fun key(channel: LiveChannel): String {
        val name = channel.name.trim().lowercase(Locale.ROOT)
            .replace(Regex("""[\s·._-]+"""), "")
            .replace(Regex("""(?:高清|超清|标清|fhd|uhd|hd|4k|1080p)$"""), "")
        return name.ifBlank { channel.tvgId.trim().lowercase(Locale.ROOT) }
    }

    fun combine(primary: List<LiveChannel>, backup: List<LiveChannel>): List<LiveChannel> {
        val seen = HashSet<String>()
        return (primary + backup).filter { channel ->
            val channelKey = key(channel)
            channelKey.isNotBlank() && channel.url.isNotBlank() &&
                seen.add(channelKey + "\u0000" + channel.url)
        }
    }

    /** Preserve the source order on failure; never reorder due to filename "4K" claims. */
    fun alternatives(target: LiveChannel, combined: List<LiveChannel>): List<LiveChannel> =
        combined.filter { key(it) == key(target) }
            .distinctBy { it.url }
}
