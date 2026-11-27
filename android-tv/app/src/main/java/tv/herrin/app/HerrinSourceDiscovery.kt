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
import kotlinx.coroutines.withContext
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
        val identity: HerrinFilmIdentity? = null,
        /** Personal adapter already returns the entire matched file playlist. */
        val bundledPersonalLayout: Boolean = false
    )
    data class EpisodeLayout(val number: Int, val title: String, val lines: List<Pair<String,String>>)
    data class Hit(val key: String, val node: Node, val movie: HerrinSourceMovie,
        val episodes: List<EpisodeLayout> = emptyList(),
        val relevance: SourceRelevance = SourceRelevance.CONFIRMED)
    data class Route(val nodeId: String, val hitKey: String, val lineId: String)
    data class Snapshot(val revision: String, val nodes: Int,
        val outcomes: Map<String, Outcome> = emptyMap(), val hits: List<Hit> = emptyList(),
        val expanding: Boolean = false, val warehouseFailures: Int = 0,
        val routes: List<Route> = emptyList(),
        val detailed: Set<String> = emptySet(), val failedDetails: Set<String> = emptySet()) {
        val readyRoutes: List<Route> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val ids = hits.associate { hit -> hit.key to hit.episodes.flatMap { it.lines }.map { it.first }.toSet() }
            routes.filter { it.lineId in ids[it.hitKey].orEmpty() }
        }
        val selectableNodeIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val ready = readyRoutes.mapTo(hashSetOf()) { it.hitKey }
            hits.asSequence().filter { hit ->
                hit.relevance == SourceRelevance.CONFIRMED && hit.key in ready &&
                    hit.key in detailed && hit.key !in failedDetails &&
                    hit.episodes.isNotEmpty() && hit.episodes.all { it.lines.isNotEmpty() }
            }.map { it.node.id }.toSet()
        }
        val confirmed = hits.filter { it.relevance == SourceRelevance.CONFIRMED }
            confirmed.groupBy { it.node.id }.filter { (nodeId, candidates) ->
                val required = if (candidates.first().node.bundledPersonalLayout)
                    candidates.take(1) else candidates
                required.all { it.key in detailed && it.key !in failedDetails } &&
                    required.any { it.episodes.isNotEmpty() &&
                        it.episodes.all { ep -> ep.lines.isNotEmpty() } &&
                        readyRoutes.any { route -> route.nodeId == nodeId && route.hitKey == it.key } }
            }.keys
        }
        val confirmed get() = hits.count { it.relevance == SourceRelevance.CONFIRMED }
        val possible get() = hits.count { it.relevance == SourceRelevance.POSSIBLE }
        val excluded get() = hits.count { it.relevance == SourceRelevance.OTHER }
        val checked get() = outcomes.count { it.value != Outcome.SEARCHING }
        val remaining get() = (nodes - checked).coerceAtLeast(0)
        val hasRemaining get() = remaining > 0 || expanding || hits.any {
            it.relevance == SourceRelevance.CONFIRMED && !it.node.bundledPersonalLayout && it.key !in detailed
        }
        fun text() = "已检查 $checked/$nodes · 原始搜索命中 ${outcomes.count { it.value == Outcome.FOUND }} 站 · 原始条目 ${hits.size} · 确认匹配 $confirmed · 可能相关 $possible · 排除 $excluded · " +
            "无结果 ${outcomes.count { it.value == Outcome.EMPTY }} · 失败 ${outcomes.count { it.value == Outcome.FAILED }} · " +
            "超时 ${outcomes.count { it.value == Outcome.TIMEOUT }} · 剩余 $remaining" +
            if(expanding) " · 正在读取其他已导入影视仓" else if(warehouseFailures > 0) " · $warehouseFailures 个影视仓读取失败，可继续重试" else ""
    }
    // UI selection owns the next Spider session. CMS/NAS lanes remain independent.
    @Volatile var spiderPaused: Boolean = false
    private val version = java.util.concurrent.atomic.AtomicLong()
    private val runs = Mutex()
    private val mutations = Mutex()
    @Volatile private var snapshot = Snapshot("", 0)
    private data class CachedDetail(val revision: String, val expiresAt: Long, val value: Detail)
    private val cmsDetails = linkedMapOf<String, CachedDetail>()

    suspend fun recentCmsDetail(revision: String, hit: Hit): Detail? = mutations.withLock {
        if (hit.node.type != HerrinSourceType.TVBOX_CMS || snapshot.revision != revision) return@withLock null
        cmsDetails[hit.key]?.takeIf { it.revision == revision && it.expiresAt >= System.nanoTime() }?.value
    }

    /** Retain each provider's actual episode/line layout without caching media authorization.
     * Selecting it again always reacquires details through that provider's callback. */
    suspend fun retainEpisodes(revision: String, key: String, detailed: Film, ticket: Long? = null): Snapshot? = mutations.withLock {
        if(snapshot.revision != revision || ticket != null && version.get() != ticket) return@withLock null
        val hit=snapshot.hits.firstOrNull{it.key==key} ?:return@withLock null
        val expected=hit.movie.film
        require(expected.cmsVodId == detailed.cmsVodId && expected.spiderVodId == detailed.spiderVodId)
        require(expected.spiderOrigin?.site == detailed.spiderOrigin?.site &&
            expected.spiderOrigin?.runtime == detailed.spiderOrigin?.runtime)
        val layout=detailed.playbackEpisodes.map { episode ->
            EpisodeLayout(episode.number,episode.title,episode.lines.map { it.id to it.title })
        }
        val routes = layout.flatMap { it.lines }.map { Route(hit.node.id, key, it.first) }.distinct()
        snapshot=snapshot.copy(hits=snapshot.hits.map{if(it.key==key)it.copy(episodes=layout) else it},
            routes = (snapshot.routes + routes).distinct(), detailed = snapshot.detailed + key, failedDetails = snapshot.failedDetails - key)
        snapshot
    }

    suspend fun invalidateLayout(revision: String, key: String): Snapshot? = mutations.withLock {
        if (snapshot.revision != revision) return@withLock null
        cmsDetails.remove(key)
        snapshot = snapshot.copy(hits = snapshot.hits.map { if (it.key == key) it.copy(episodes = emptyList()) else it },
            // A failed selection is terminal until its explicit node retry.
            // Lifecycle continuation must not silently prefetch it again.
            detailed = snapshot.detailed + key, failedDetails = snapshot.failedDetails + key)
        snapshot
    }

    /** Keep route ordinals as tombstones, while retrying only the explicitly requested node. */
    suspend fun resetNode(revision: String, nodeId: String): Snapshot? = mutations.withLock {
        if (snapshot.revision != revision) return@withLock null
        val keys = snapshot.hits.filter { it.node.id == nodeId }.map { it.key }.toSet()
        keys.forEach(cmsDetails::remove)
        snapshot = snapshot.copy(outcomes = snapshot.outcomes - nodeId,
            hits = snapshot.hits.filterNot { it.node.id == nodeId }, detailed = snapshot.detailed - keys, failedDetails = snapshot.failedDetails - keys)
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
            hits = snapshot.hits.filter { it.node.id in known }.map { it.copy(node = known.getValue(it.node.id)) },
            routes = snapshot.routes.filter { it.nodeId in known },
            detailed = snapshot.detailed.intersect(snapshot.hits.filter { it.node.id in known }.map { it.key }.toSet()))
        snapshot = snapshot.copy(expanding = expand != null, warehouseFailures = 0)
        val startedAt = System.nanoTime()
        var firstReady = false
        suspend fun deliver() {
            if (!firstReady && snapshot.selectableNodeIds.isNotEmpty()) {
                firstReady = true
                HerrinPlaybackDiagnosticJournal.record("LOOKUP", "DISCOVERY_FIRST_PLAYABLE_LAYOUT",
                    "MS=${(System.nanoTime() - startedAt) / 1_000_000}")
            }
            publish(snapshot)
        }
        deliver()
        val cmsSlots = Semaphore(6)
        val detailSlots = Semaphore(3)
        suspend fun spiderOperation(block: suspend () -> Unit) {
            while (true) {
                while (spiderPaused) { coroutineContext.ensureActive(); kotlinx.coroutines.delay(100) }
                val executed = spiderLock.withLock {
                    if (spiderPaused) false else { block(); true }
                }
                if (executed) return
            }
        }
        coroutineScope {
            val owner = this
            val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val perNodeDetails = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()
            suspend fun load(node: Node, hit: Hit) {
                val started = System.nanoTime()
                try {
                    val detail = withTimeoutOrNull(node.timeoutMs) {
                        if (node.type == HerrinSourceType.TVBOX_SPIDER)
                            withContext(SpiderRequestBudget(node.timeoutMs)) { node.details(hit.movie) }
                        else node.details(hit.movie)
                    }
                    coroutineContext.ensureActive()
                    if (detail != null) {
                        retainEpisodes(revision, hit.key, detail.film, ticket)?.let {
                            mutations.withLock {
                                if (snapshot.revision == revision && version.get() == ticket) {
                                    if (node.type == HerrinSourceType.TVBOX_CMS && detail.film.playbackEpisodes.isNotEmpty()) {
                                        if (cmsDetails.size >= 16) cmsDetails.remove(cmsDetails.keys.first())
                                        cmsDetails[hit.key] = CachedDetail(revision, System.nanoTime() + 20_000_000_000L, detail)
                                    }
                                    deliver()
                                }
                            }
                            HerrinPlaybackDiagnosticJournal.record("LOOKUP", "DISCOVERY_LAYOUT_READY",
                                "MS=${(System.nanoTime() - started) / 1_000_000}")
                        }
                    } else mutations.withLock {
                        if (snapshot.revision == revision && version.get() == ticket) {
                            snapshot = snapshot.copy(detailed = snapshot.detailed + hit.key,
                                failedDetails = snapshot.failedDetails + hit.key)
                            deliver()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // Failure is terminal for this pass, but not a complete
                    // node layout. Keep it hidden until explicit node retry.
                    mutations.withLock {
                        if (snapshot.revision == revision && version.get() == ticket) {
                            snapshot = snapshot.copy(detailed = snapshot.detailed + hit.key, failedDetails = snapshot.failedDetails + hit.key)
                            deliver()
                        }
                    }
                }
            }
            fun prefetch(node: Node) {
                val matches = snapshot.hits.filter { it.node.id == node.id && it.relevance == SourceRelevance.CONFIRMED }
                val candidates = if (node.bundledPersonalLayout) matches.take(1) else matches
                candidates.filter { it.key !in snapshot.detailed }.forEach { hit ->
                    if (!queued.add(hit.key)) return@forEach
                    owner.launch {
                        // A node with many versions cannot fill the entire global
                        // queue ahead of every other provider's first candidate.
                        perNodeDetails.getOrPut(node.id) { Semaphore(1) }.withPermit {
                            if (node.type == HerrinSourceType.TVBOX_SPIDER) spiderOperation { load(node, hit) }
                            else detailSlots.withPermit { load(node, hit) }
                        }
                    }
                }
            }
            suspend fun run(node: Node) {
                if (snapshot.outcomes[node.id]?.let { it != Outcome.SEARCHING } == true) {
                    prefetch(node)
                    return
                }
                suspend fun update(outcome: Outcome, offers: List<HerrinSourceMovie> = emptyList()) {
                    mutations.withLock {
                        coroutineContext.ensureActive()
                        if (version.get() != ticket || snapshot.revision != revision) return@withLock
                        val hits = offers.map { movie ->
                            Hit(node.id + ":" + (movie.film.spiderVodId ?: movie.film.cmsVodId ?: movie.film.id), node, movie,
                                relevance = node.identity?.let { HerrinSourceRelevance.classify(it, movie) } ?: SourceRelevance.CONFIRMED)
                        }
                        snapshot = snapshot.copy(outcomes = snapshot.outcomes + (node.id to outcome),
                            hits = if (outcome == Outcome.SEARCHING) snapshot.hits else
                                (snapshot.hits.filterNot { it.node.id == node.id } + hits).distinctBy { it.key })
                        // The initial plan already exposes pending work. Do not
                        // hold the mutation lock and a network slot for a Main
                        // roundtrip before each request has even started.
                        // Results/ready layouts/failures still publish immediately.
                        if (outcome != Outcome.SEARCHING) deliver()
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
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: SourceCandidateFailure) {
                        failureStage = failure.stage; failureCode = failure.outcome; update(Outcome.FAILED)
                    } catch (failure: SourceHttpFailure) {
                        failureCode = SourceHealthIndex.httpOutcome(failure.code); update(Outcome.FAILED)
                    } catch (_: Exception) { update(Outcome.FAILED) }
                    diagnose(SourceSearchEvent(node.id, failureStage,
                        failureCode ?: when(snapshot.outcomes[node.id]) {
                            Outcome.FOUND -> SourceSearchOutcome.VERIFIED // SEARCH stage only; never media verification.
                            Outcome.EMPTY -> SourceSearchOutcome.NO_MATCH
                            Outcome.TIMEOUT -> SourceSearchOutcome.TIMEOUT
                            else -> SourceSearchOutcome.ERROR
                        }, (System.nanoTime() - started) / 1_000_000, completed = snapshot.checked, total = snapshot.nodes, nodeComplete = true))
                }
                if (node.type == HerrinSourceType.TVBOX_SPIDER) spiderOperation {
                    execute()
                    // Reuse this still-authorized session for the first matching
                    // layout before queued searches can replace it. Never resolve media.
                    if (!spiderPaused) snapshot.hits.firstOrNull { it.node.id == node.id &&
                        it.relevance == SourceRelevance.CONFIRMED && it.key !in snapshot.detailed }?.let { hit ->
                        if (queued.add(hit.key)) load(node, hit)
                    }
                } else cmsSlots.withPermit { execute() }
                prefetch(node)
            }
            val personal = unique.filter { it.type == HerrinSourceType.NAS }
            // Start priority requests first, without making every other node
            // wait for a slow preferred provider's entire timeout budget.
            personal.forEach { node -> launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { run(node) } }
            unique.firstOrNull { it.id == preferred && it !in personal }?.let { node ->
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { run(node) }
            }
            unique.filter { it !in personal && it.id != preferred }.forEach { node ->
                // Claim the bounded slots in ranked order, independent of IO scheduling.
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { run(node) }
            }
            if (expand != null) launch {
                val failures = expand { additions ->
                    val fresh = mutations.withLock {
                        if (version.get() != ticket || snapshot.revision != revision) return@withLock emptyList<Node>()
                        val added = additions.distinctBy { it.id }.filter { it.id !in known }
                        added.forEach { known[it.id] = it }
                        snapshot = snapshot.copy(nodes = known.size)
                        deliver()
                        added
                    }
                    fresh.forEach { node -> launch { run(node) } }
                }
                mutations.withLock {
                    if (version.get() != ticket || snapshot.revision != revision) return@withLock
                    snapshot = snapshot.copy(expanding = false, warehouseFailures = failures)
                    deliver()
                }
            }
        }
        HerrinPlaybackDiagnosticJournal.record("LOOKUP", "DISCOVERY_ALL_NODES_FINISHED",
            "MS=${(System.nanoTime() - startedAt) / 1_000_000}")
        }
    }
}
