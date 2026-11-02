package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Priority is strict: NAS/cloud first. TVBox is queried only when no identity
 * match exists in personal sources. Within a tier prefer verified media metadata
 * later during actual playback; source title claims are merely a tie breaker.
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
        for (tier in listOf(HerrinSourceTier.PERSONAL, HerrinSourceTier.TVBOX)) {
            val results = mutableListOf<HerrinSourceMovie>()
            for (source in sources.filter { source ->
                if (tier == HerrinSourceTier.PERSONAL)
                    source.type == HerrinSourceType.NAS || source.type == HerrinSourceType.CLOUD
                else source.type in setOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER)
            }.distinctBy { it.id }.take(16)) {
                searched.add(source.id)
                try {
                    val matches = withTimeoutOrNull(6_000L) { source.search(identity) }
                    if (matches == null) {
                        errors[source.id] = "搜索超时"
                        continue
                    }
                    results += matches.take(80).filter {
                        it.sourceId == source.id && it.sourceType == source.type &&
                            HerrinFilmIdentityMatch.matches(identity, it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errors[source.id] = "来源检索失败"
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
