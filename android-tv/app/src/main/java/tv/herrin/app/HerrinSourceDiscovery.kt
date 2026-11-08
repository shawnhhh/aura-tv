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
        val timeoutMs: Long = 12_000L
    )
    data class Hit(val key: String, val node: Node, val movie: HerrinSourceMovie)
    data class Snapshot(val revision: String, val nodes: Int,
        val outcomes: Map<String, Outcome> = emptyMap(), val hits: List<Hit> = emptyList()) {
        val checked get() = outcomes.count { it.value != Outcome.SEARCHING }
        val remaining get() = nodes - checked
        fun text() = "已检查 $checked/$nodes · 命中 ${outcomes.count { it.value == Outcome.FOUND }} · " +
            "无结果 ${outcomes.count { it.value == Outcome.EMPTY }} · 失败 ${outcomes.count { it.value == Outcome.FAILED }} · " +
            "超时 ${outcomes.count { it.value == Outcome.TIMEOUT }} · 剩余 $remaining"
    }
    private val runs = Mutex()
    private val mutations = Mutex()
    private var snapshot = Snapshot("", 0)

    /** Cancelled nodes stay pending; only terminal nodes are skipped by a continuation. */
    suspend fun search(revision: String, nodes: List<Node>, preferred: String?,
        spiderLock: Mutex, diagnose: (SourceSearchEvent) -> Unit = {}, publish: suspend (Snapshot) -> Unit) = runs.withLock {
        val unique = nodes.distinctBy { it.id }
        if (snapshot.revision != revision) snapshot = Snapshot(revision, unique.size)
        else snapshot = snapshot.copy(nodes = unique.size,
            outcomes = snapshot.outcomes.filterValues { it != Outcome.SEARCHING })
        publish(snapshot)
        val cmsSlots = Semaphore(4)
        suspend fun run(node: Node) {
            if (snapshot.outcomes[node.id]?.let { it != Outcome.SEARCHING } == true) return
            suspend fun update(outcome: Outcome, offers: List<HerrinSourceMovie> = emptyList()) {
                mutations.withLock {
                    coroutineContext.ensureActive()
                    val hits = offers.map { movie ->
                        // A VOD belongs to a provider, never to a TMDB id or array index.
                        Hit(node.id + ":" + (movie.film.spiderVodId ?: movie.film.cmsVodId ?: movie.film.id), node, movie)
                    }
                    snapshot = snapshot.copy(outcomes = snapshot.outcomes + (node.id to outcome),
                        hits = (snapshot.hits + hits).distinctBy { it.key })
                    publish(snapshot)
                }
            }
            suspend fun execute() {
                update(Outcome.SEARCHING)
                val started = System.nanoTime()
                try {
                    val result = withTimeoutOrNull(node.timeoutMs) { node.search() }
                    update(if (result == null) Outcome.TIMEOUT else if (result.isEmpty()) Outcome.EMPTY else Outcome.FOUND,
                        result.orEmpty())
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { update(Outcome.FAILED) }
                diagnose(SourceSearchEvent(node.id, SourceSearchStage.SEARCH,
                    when(snapshot.outcomes[node.id]) {
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
        coroutineScope { unique.filter { it !in personal && it.id != preferred }.forEach { node -> launch { run(node) } } }
    }
}
