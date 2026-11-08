package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** In-memory, configuration-scoped provider results. No media resolution in this layer. */
internal class HerrinSourceDiscovery {
    enum class Outcome { SEARCHING, FOUND, EMPTY, FAILED, TIMEOUT }
    data class Detail(val film: Film, val personal: Map<String, PlaybackMedia> = emptyMap())
    class Node(
        val id: String, val name: String, val type: HerrinSourceType,
        val search: suspend () -> List<HerrinSourceMovie>,
        val details: suspend (HerrinSourceMovie) -> Detail?,
        val site: TvBoxSite? = null,
        val config: TvBoxConfig.Single? = null,
        val timeoutMs: Long = 12_000L,
        val identity: HerrinFilmIdentity? = null
    )
    data class EpisodeLayout(val number: Int, val title: String, val lines: List<Pair<String,String>>)
    data class Hit(val key: String, val node: Node, val movie: HerrinSourceMovie,
        val episodes: List<EpisodeLayout> = emptyList(),
        val relevance: SourceRelevance = SourceRelevance.CONFIRMED)
    data class Snapshot(val revision: String, val nodes: Int,
        val outcomes: Map<String, Outcome> = emptyMap(), val hits: List<Hit> = emptyList(),
        val expanding: Boolean = false, val warehouseFailures: Int = 0) {
        val confirmed get() = hits.count { it.relevance == SourceRelevance.CONFIRMED }
        val possible get() = hits.count { it.relevance == SourceRelevance.POSSIBLE }
        val excluded get() = hits.count { it.relevance == SourceRelevance.OTHER }
        val checked get() = outcomes.count { it.value != Outcome.SEARCHING }
        val remaining get() = (nodes - checked).coerceAtLeast(0)
        val hasRemaining get() = remaining > 0 || expanding || warehouseFailures > 0
        fun text() = "已检查 $checked/$nodes · 原始条目 ${hits.size} · 确认匹配 $confirmed · 可能相关 $possible · 排除 $excluded · " +
            "无结果 ${outcomes.count { it.value == Outcome.EMPTY }} · 失败 ${outcomes.count { it.value == Outcome.FAILED }} · " +
            "超时 ${outcomes.count { it.value == Outcome.TIMEOUT }} · 剩余 $remaining" +
            if(expanding) " · 正在读取其他已导入影视仓" else if(warehouseFailures > 0) " · $warehouseFailures 个影视仓读取失败，可继续重试" else ""
    }
    private val version = java.util.concurrent.atomic.AtomicLong()
    private val runs = Mutex()
    private val mutations = Mutex()
    private var snapshot = Snapshot("", 0)

    /** Retain each provider's actual episode/line layout without caching media authorization.
     * Selecting it again always reacquires details through that provider's callback. */
    suspend fun retainEpisodes(revision: String, key: String, detailed: Film): Snapshot? = mutations.withLock {
        if(snapshot.revision != revision) return@withLock null
        val hit=snapshot.hits.firstOrNull{it.key==key} ?:return@withLock null
        val expected=hit.movie.film
        require(expected.cmsVodId == detailed.cmsVodId && expected.spiderVodId == detailed.spiderVodId)
        val layout=detailed.playbackEpisodes.map { episode ->
            EpisodeLayout(episode.number,episode.title,episode.lines.map { it.id to it.title })
        }
        snapshot=snapshot.copy(hits=snapshot.hits.map{if(it.key==key)it.copy(episodes=layout) else it})
        snapshot
    }

    /** Cancelled nodes stay pending; only terminal nodes are skipped by a continuation. */
    suspend fun search(revision: String, nodes: List<Node>, preferred: String?,
        spiderLock: Mutex, diagnose: (SourceSearchEvent) -> Unit = {},
        expand: (suspend (suspend (List<Node>) -> Unit) -> Int)? = null, publish: suspend (Snapshot) -> Unit) {
        val ticket = version.incrementAndGet()
        runs.withLock {
        if (version.get() != ticket) return@withLock
        val unique = nodes.distinctBy { it.id }
        val known = unique.associateBy { it.id }.toMutableMap()
        if (snapshot.revision != revision) snapshot = Snapshot(revision, unique.size)
        else snapshot = snapshot.copy(nodes = unique.size,
            outcomes = snapshot.outcomes.filter { it.key in known && it.value != Outcome.SEARCHING },
            hits = snapshot.hits.filter { it.node.id in known })
        snapshot = snapshot.copy(expanding = expand != null, warehouseFailures = 0)
        publish(snapshot)
        val cmsSlots = Semaphore(4)
        suspend fun run(node: Node) {
            if (snapshot.outcomes[node.id]?.let { it != Outcome.SEARCHING } == true) return
            suspend fun update(outcome: Outcome, offers: List<HerrinSourceMovie> = emptyList()) {
                mutations.withLock {
                    coroutineContext.ensureActive()
                    if (version.get() != ticket || snapshot.revision != revision) return@withLock
                    val hits = offers.map { movie ->
                        // A VOD belongs to a provider, never to a TMDB id or array index.
                        Hit(node.id + ":" + (movie.film.spiderVodId ?: movie.film.cmsVodId ?: movie.film.id), node, movie,
                            relevance = node.identity?.let { HerrinSourceRelevance.classify(it, movie) } ?: SourceRelevance.CONFIRMED)
                    }
                    snapshot = snapshot.copy(outcomes = snapshot.outcomes + (node.id to outcome),
                        hits = (snapshot.hits + hits).distinctBy { it.key })
                    publish(snapshot)
                }
            }
            suspend fun execute() {
                update(Outcome.SEARCHING)
                val started = System.nanoTime()
                var failureStage = SourceSearchStage.SEARCH
                var failureCode: SourceSearchOutcome? = null
                try {
                    val result = withTimeoutOrNull(node.timeoutMs) { node.search() }
                    update(if (result == null) Outcome.TIMEOUT else if (result.isEmpty()) Outcome.EMPTY else Outcome.FOUND,
                        result.orEmpty())
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: SourceCandidateFailure) {
                    failureStage = failure.stage; failureCode = failure.outcome; update(Outcome.FAILED)
                } catch (failure: SourceHttpFailure) {
                    failureCode = SourceHealthIndex.httpOutcome(failure.code); update(Outcome.FAILED)
                } catch (_: Exception) { update(Outcome.FAILED) }
                diagnose(SourceSearchEvent(node.id, failureStage,
                    failureCode ?: when(snapshot.outcomes[node.id]) {
                        Outcome.FOUND -> SourceSearchOutcome.VERIFIED
                        Outcome.EMPTY -> SourceSearchOutcome.NO_MATCH
                        Outcome.TIMEOUT -> SourceSearchOutcome.TIMEOUT
                        else -> SourceSearchOutcome.ERROR
                    }, (System.nanoTime() - started) / 1_000_000, completed = snapshot.checked, total = snapshot.nodes, nodeComplete = true))
            }
            if (node.type == HerrinSourceType.TVBOX_SPIDER) spiderLock.withLock { execute() }
            else cmsSlots.withPermit { execute() }
        }
        val personal = unique.filter { it.type == HerrinSourceType.NAS || it.type == HerrinSourceType.CLOUD }
        personal.forEach { run(it) }
        unique.firstOrNull { it.id == preferred && it !in personal }?.let { run(it) }
        // Independent lanes: queued Spider calls do not occupy the CMS slots.
        coroutineScope {
            unique.filter { it !in personal && it.id != preferred }.forEach { node -> launch { run(node) } }
            if(expand != null) launch {
                val failures = expand { additions ->
                    val fresh = mutations.withLock {
                        val added = additions.distinctBy { it.id }.filter { it.id !in known }
                        added.forEach { known[it.id] = it }
                        snapshot = snapshot.copy(nodes = known.size)
                        publish(snapshot)
                        added
                    }
                    fresh.forEach { node -> launch { run(node) } }
                }
                mutations.withLock {
                    snapshot = snapshot.copy(expanding = false, warehouseFailures = failures)
                    publish(snapshot)
                }
            }
        }
    }
    }
}
