/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.herrin.kernel.Kernel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal suspend fun <T> CompletionStage<T>.awaitKernel(): T = suspendCancellableCoroutine { continuation ->
    val future = toCompletableFuture()
    future.whenComplete { value, failure ->
        if (continuation.isActive) {
            if (failure == null) continuation.resume(value) else continuation.resumeWithException(
                if (failure is java.util.concurrent.CompletionException) failure.cause ?: failure else failure)
        }
    }
    continuation.invokeOnCancellation { future.cancel(false) }
}

/** Projection into existing Film/episode layouts; never decodes source JSON. */
internal class HerrinKernelPages(private val runtime: HerrinKernelRuntime) {
    private val requests = Mutex()
    @Volatile private var warehouse = ""
    @Volatile private var allowHttp = false
    @Volatile private var site: TvBoxSite? = null
    @Volatile private var session = UUID.randomUUID().toString()
    @Volatile private var authority: SpiderFilmAuthority? = null
    private val movies = java.util.concurrent.ConcurrentHashMap<String, Kernel.Movie>()
    private val selections = java.util.concurrent.ConcurrentHashMap<String, KernelPlaybackTicket>()
    private val latest = java.util.concurrent.ConcurrentHashMap<String, Film>()
    private val coordinates = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()
    var selectedCoordinate by androidx.compose.runtime.mutableStateOf<Pair<Int, String>?>(null)
        private set
    private var currentEpisode = ""
    private var currentPhase = Kernel.Phase.IDLE
    private var lastRequestedEpisode = ""
    var revision by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    fun warehouse(root: String, cleartext: Boolean) { warehouse = TvBoxUrl.normalize(root, cleartext).orEmpty(); allowHttp = cleartext }
    fun activeSite(): String? = site?.key
    fun owns(ticket: KernelPlaybackTicket) = ticket.session == session && selections[ticket.episode]?.session == session
    fun onState(state: Kernel.State) {
        currentEpisode = state.selectedEpisodeHandle()
        currentPhase = state.phase()
        selectedCoordinate = coordinates[currentEpisode]
    }
    fun alreadyPlaying(ticket: KernelPlaybackTicket) = owns(ticket) && currentEpisode == ticket.episode &&
        currentPhase in setOf(Kernel.Phase.RESOLVING, Kernel.Phase.BUFFERING, Kernel.Phase.PLAYING, Kernel.Phase.PAUSED)
    fun owns(film: Film) = authority?.accepts(film) == true && film.spiderVodId?.let(movies::containsKey) == true
    fun ticket(film: Film, number: Int, line: String): KernelPlaybackTicket {
        require(owns(film)) { "Kernel movie session expired" }
        require(film.playbackEpisodes.any { it.number == number && it.lines.any { choice -> choice.id == line } })
        return requireNotNull(selections[line]) { "Kernel episode selection expired" }
    }
    suspend fun browse(selected: TvBoxSite, config: TvBoxConfig.Single): SpiderResult<List<Film>> = result {
        require(warehouse.isNotBlank()) { "Warehouse authorization is missing" }
        // The compile-ready build starts in whole-core mode. Legacy execution
        // is barred before activation; no live legacy worker may coexist.
        runtime.activate(runtime::retireLegacy).awaitKernel()
        session = UUID.randomUUID().toString()
        val selectedSession = session
        movies.clear(); selections.clear(); latest.clear(); coordinates.clear(); lastRequestedEpisode = ""
        site = selected
        authority = SpiderFilmAuthority(selected.key,
            SpiderRuntimeCompat.sessionKey(selected.jar ?: config.spiderJar ?: selected.scriptApi.orEmpty(), selected),
            UUID.randomUUID().toString())
        runtime.gateway.load(Kernel.WarehouseAccess(URI(warehouse), allowHttp, true, listOf(selected.key))).awaitKernel()
        check(selectedSession == session) { "Warehouse selection changed" }
        runtime.gateway.observeDetail { detail ->
            if (selectedSession == session && detail.movie().siteKey() == site?.key) projectDetail(detail)
        }.awaitKernel()
        val home = runtime.gateway.home(selected.key).awaitKernel()
        check(selectedSession == session)
        home.map(::projectMovie)
    }
    suspend fun search(selected: TvBoxSite, query: String): SpiderResult<List<Film>> = result {
        require(selected.key == site?.key) { "Search site differs from active kernel session" }
        val selectedSession = session
        val found = runtime.gateway.search(selected.key, query).awaitKernel()
        check(selectedSession == session)
        found.map(::projectMovie)
    }
    suspend fun details(film: Film): SpiderResult<Film> = result {
        require(owns(film)) { "Kernel movie session expired" }
        val selectedSession = session
        val detail = runtime.gateway.details(requireNotNull(film.spiderVodId)).awaitKernel()
        check(selectedSession == session)
        projectDetail(detail)
    }
    @Synchronized fun media(film: Film, number: Int, line: String): PlaybackResult = try {
        val selected = ticket(film, number, line).copy(replay = lastRequestedEpisode == line)
        lastRequestedEpisode = line
        PlaybackResult.Ready(PlaybackMedia("kernel-selection:$line", kernelPlayback = selected))
    } catch (_: IllegalArgumentException) { PlaybackResult.Unavailable("来源会话或选集已变化，请返回详情重新选择") }
    fun latest(film: Film): Film? = film.spiderVodId?.let(latest::get)
    private fun projectMovie(movie: Kernel.Movie): Film {
        require(movie.siteKey() == site?.key) { "Movie belongs to a different site session" }
        movies[movie.handle()] = movie
        return requireNotNull(authority).sign(Film("kernel-${movie.handle()}", movie.title(), movie.remarks(), TvBoxCategories.topLevel(movie.category()),
            movie.year(), SourceText.synopsis(movie.synopsis()), R.drawable.photofilm_orbit, artworkUrl = TvBoxUrl.normalize(movie.artwork(), true),
            spiderVodId = movie.handle()))
    }
    private fun projectDetail(detail: Kernel.Detail): Film {
        val movie = detail.movie()
        val base = projectMovie(movie)
        val episodes = linkedMapOf<Int, MutableList<PlaybackLine>>()
        val names = mutableMapOf<Int, String>()
        detail.lines().forEach { line -> line.episodes().forEach { episode ->
            val number = episode.index() + 1
            selections[episode.handle()] = KernelPlaybackTicket(session, episode.handle())
            coordinates[episode.handle()] = number to episode.handle()
            names[number] = episode.title()
            episodes.getOrPut(number) { mutableListOf() }.add(
                PlaybackLine(episode.handle(), line.name(), "kernel-selection:${episode.handle()}"))
        } }
        val projected = requireNotNull(authority).sign(base.copy(episodes = episodes.size,
            playbackEpisodes = episodes.map { (number, lines) -> PlaybackEpisode(number, names[number].orEmpty(), lines) }))
        latest[movie.handle()] = projected
        revision++
        return projected
    }
    fun releasePlayback() { runtime.gateway.stop() }
    fun close() {
        session = UUID.randomUUID().toString()
        movies.clear(); selections.clear(); latest.clear(); coordinates.clear(); site = null; authority = null
        selectedCoordinate = null; currentEpisode = ""; currentPhase = Kernel.Phase.IDLE; lastRequestedEpisode = ""
        runtime.gateway.stop()
    }
    private suspend fun <T> result(block: suspend () -> T): SpiderResult<T> = try { requests.withLock { SpiderResult.Ready(block()) } }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) { SpiderResult.Failure("原版内核请求失败；未回退旧播放系统") }
}
