package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

/** Personal adapters share one source router, playlist builder and existing FongMi player. */
internal interface HerrinPersonalSource:HerrinFilmSource {
    fun playback(offer:HerrinSourceMovie):PlaybackResult
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
internal class HerrinFilmRouter(private val sources: List<HerrinFilmSource>, private val preferredSourceId: String? = null,
    private val validationBudgetMs: Long = 17_000L) {
    init { require(validationBudgetMs in 1..17_000L) }
    /**
     * Verify actual resource usability before accepting a priority tier.
     * If every matched NAS/cloud offer is unusable, continue to TVBox.
     * For TVBox a verifier can try multiple candidates' details/episode lists.
     */
    suspend fun find(
        identity: HerrinFilmIdentity,
        streaming: Boolean = false,
        health: SourceHealthIndex? = null,
        onEvent: suspend (SourceSearchEvent) -> Unit = {},
        accept: suspend (List<HerrinSourceMovie>) -> List<HerrinSourceMovie> = { it }
    ): HerrinFilmLookupResult {
        if (streaming) return firstVerified(identity,health,onEvent,accept)
        val errors = linkedMapOf<String, String>()
        val searched = mutableListOf<String>()
        val preferred = sources.firstOrNull { it.id == preferredSourceId &&
            it.type in listOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER) }
        val sourceStages = listOf(
            sources.filter { it.type == HerrinSourceType.NAS },
            sources.filter { it.type == HerrinSourceType.CLOUD },
            listOfNotNull(preferred),
            sources.filter { it.type in listOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER) && it !== preferred }
        )
        for (stage in sourceStages) {
            if (stage.isEmpty()) continue
            val tier = if (stage.first().type in listOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER))
                HerrinSourceTier.TVBOX else HerrinSourceTier.PERSONAL
            val results = mutableListOf<HerrinSourceMovie>()
            val stageSources = stage.distinctBy { it.id }.take(16)
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
            val groups: List<List<HerrinFilmSource>> =
                if (tier == HerrinSourceTier.TVBOX)
                    stageSources.filter { it.type == HerrinSourceType.TVBOX_CMS }.chunked(4) +
                        stageSources.filter {
                            it.type == HerrinSourceType.TVBOX_SPIDER
                        }.map { listOf(it) }
                else stageSources.map { listOf(it) }
            for (group in groups) {
                val shouldParallelize = tier == HerrinSourceTier.TVBOX &&
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

    /** Search and validation have separate deadlines; a completed search does not consume Media3's budget. */
    private suspend fun firstVerified(identity: HerrinFilmIdentity, health: SourceHealthIndex?,
        onEvent: suspend (SourceSearchEvent)->Unit,
        accept: suspend (List<HerrinSourceMovie>)->List<HerrinSourceMovie>): HerrinFilmLookupResult {
        val errors = linkedMapOf<String, String>()
        val searched = mutableListOf<String>()
        var completed = 0
        val preferred = sources.firstOrNull { it.id == preferredSourceId &&
            it.type in listOf(HerrinSourceType.TVBOX_CMS, HerrinSourceType.TVBOX_SPIDER) }
        val stages = listOf(
            sources.filter { it.type == HerrinSourceType.NAS },
            sources.filter { it.type == HerrinSourceType.CLOUD },
            listOfNotNull(preferred),
            sources.filter { it.type == HerrinSourceType.TVBOX_CMS && it !== preferred },
            sources.filter { it.type == HerrinSourceType.TVBOX_SPIDER && it !== preferred })
        data class Found(val source: HerrinFilmSource, val offers: List<HerrinSourceMovie>,
            val outcome: SourceSearchOutcome, val elapsed: Long)
        suspend fun fetch(source: HerrinFilmSource, budget: Long): Found {
            onEvent(SourceSearchEvent(source.id, SourceSearchStage.SEARCH, SourceSearchOutcome.CHECKING,
                completed = completed, total = sources.size))
            val began = System.nanoTime()
            var outcome = SourceSearchOutcome.NO_MATCH
            val offers = try {
                withTimeoutOrNull(budget) { source.search(identity) }.also {
                    if (it == null) outcome = SourceSearchOutcome.TIMEOUT
                }.orEmpty().take(80).filter { it.sourceId == source.id && it.sourceType == source.type &&
                    HerrinFilmIdentityMatch.matches(identity, it) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: SourceHttpFailure) {
                outcome = SourceHealthIndex.httpOutcome(error.code)
                health?.record(source.id, SourceSearchStage.SEARCH, outcome, 0, error.retryAfterMs)
                emptyList()
            } catch (error: SourceCandidateFailure) { outcome = error.outcome; emptyList() }
            catch (_: Exception) { outcome = SourceSearchOutcome.NETWORK; emptyList() }
            if (offers.isNotEmpty()) outcome = SourceSearchOutcome.VERIFIED
            return Found(source, offers, outcome, (System.nanoTime()-began)/1_000_000)
        }
        suspend fun consume(found: Found): List<HerrinSourceMovie> {
            val source = found.source
            searched += source.id
            if (found.outcome != SourceSearchOutcome.VERIFIED && found.outcome != SourceSearchOutcome.NO_MATCH)
                errors[source.id] = found.outcome.name
            if (found.outcome !in setOf(SourceSearchOutcome.RATE_LIMITED, SourceSearchOutcome.AUTH_REQUIRED))
                health?.record(source.id, SourceSearchStage.SEARCH, found.outcome, found.elapsed)
            onEvent(SourceSearchEvent(source.id, SourceSearchStage.SEARCH, found.outcome,
                found.elapsed, completed + if (found.offers.isEmpty()) 1 else 0, sources.size, nodeComplete = found.offers.isEmpty()))
            if (found.offers.isEmpty()) { completed++; return emptyList() }
            val began = System.nanoTime()
            // Existing per-phase bounds: details 4s + parse 9s + media 4s.
            // This is independent of the 1-2s passive search deadline.
            val accepted = if (found.offers.isEmpty()) emptyList() else
                withTimeoutOrNull(validationBudgetMs) { accept(found.offers).filter { it in found.offers } }
            completed++
            if (accepted == null) {
                errors[source.id] = SourceSearchOutcome.TIMEOUT.name
                health?.record(source.id, SourceSearchStage.MEDIA, SourceSearchOutcome.TIMEOUT, (System.nanoTime()-began)/1_000_000)
                onEvent(SourceSearchEvent(source.id, SourceSearchStage.MEDIA, SourceSearchOutcome.TIMEOUT,
                    (System.nanoTime()-began)/1_000_000, completed, sources.size, nodeComplete = true))
            } else onEvent(SourceSearchEvent(source.id, SourceSearchStage.DISCOVER, SourceSearchOutcome.CHECKING,
                completed = completed, total = sources.size, nodeComplete = true))
            return accepted.orEmpty()
        }
        for (stage in stages) {
            val isPreferred = stage.singleOrNull() === preferred && preferred != null
            val providers = stage.distinctBy { it.id }.filter { isPreferred || health?.eligible(it.id) != false }
                .let { if (isPreferred) it else it.sortedByDescending { source -> health?.rank(source.id) ?: 0 } }.take(100)
            val type = providers.firstOrNull()?.type ?: continue
            val tier = if (type in listOf(HerrinSourceType.NAS, HerrinSourceType.CLOUD)) HerrinSourceTier.PERSONAL else HerrinSourceTier.TVBOX
            val accepted = if (type == HerrinSourceType.TVBOX_CMS && !isPreferred) coroutineScope {
                val channel = kotlinx.coroutines.channels.Channel<Found>(4)
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                val deadline = System.nanoTime() + 1_800_000_000L
                val jobs = providers.map { source -> launch {
                    gate.acquire()
                    try {
                        val remaining = (deadline-System.nanoTime())/1_000_000
                        channel.send(if (remaining > 0) fetch(source, minOf(1_000L, remaining))
                            else Found(source, emptyList(), SourceSearchOutcome.TIMEOUT, 0))
                    } finally { gate.release() }
                } }
                try {
                    var result = emptyList<HerrinSourceMovie>()
                    repeat(providers.size) {
                        result = consume(channel.receive())
                        if (result.isNotEmpty()) return@coroutineScope result
                    }
                    result
                } finally { jobs.forEach { it.cancel() }; channel.cancel() }
            } else {
                var result = emptyList<HerrinSourceMovie>()
                // Stateful Spider keeps search -> details -> verification serial under one owner.
                for (source in providers) {
                    result = consume(fetch(source, if (isPreferred) 2_000L else if (type == HerrinSourceType.TVBOX_SPIDER) 1_800L else 1_000L))
                    if (result.isNotEmpty()) break
                }
                result
            }
            if (accepted.isNotEmpty()) return HerrinFilmLookupResult(accepted, tier, errors, searched)
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
