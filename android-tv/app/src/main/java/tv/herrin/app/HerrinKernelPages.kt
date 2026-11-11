/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import tv.herrin.kernel.Kernel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal suspend fun <T> CompletionStage<T>.awaitKernel(): T = suspendCancellableCoroutine { continuation ->
    val future = toCompletableFuture()
    future.whenComplete { value, failure ->
        if (continuation.isActive) {
            if (failure == null) continuation.resume(value) else continuation.resumeWithException(failure)
        }
    }
    continuation.invokeOnCancellation { future.cancel(false) }
}

/** Projection into existing Film/episode layouts; never decodes source JSON. */
internal class HerrinKernelPages(private val runtime: HerrinKernelRuntime) {
    private var warehouse = ""
    private var allowHttp = false
    private var site: TvBoxSite? = null
    private var session = UUID.randomUUID().toString()
    private var authority: SpiderFilmAuthority? = null
    private val movies = java.util.concurrent.ConcurrentHashMap<String, Kernel.Movie>()
    private val selections = java.util.concurrent.ConcurrentHashMap<String, KernelPlaybackTicket>()
    private val latest = java.util.concurrent.ConcurrentHashMap<String, Film>()
    var revision by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    fun warehouse(root: String, cleartext: Boolean) { warehouse = root; allowHttp = cleartext }
    fun activeSite(): String? = site?.key
    fun owns(ticket: KernelPlaybackTicket) = ticket.session == session && selections[ticket.episode] == ticket
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
        movies.clear(); selections.clear(); latest.clear()
        site = selected
        authority = SpiderFilmAuthority(selected.key,
            SpiderRuntimeCompat.sessionKey(selected.jar ?: config.spiderJar ?: selected.scriptApi.orEmpty(), selected),
            UUID.randomUUID().toString())
        runtime.gateway.load(Kernel.WarehouseAccess(URI(warehouse), allowHttp, true, listOf(selected.key))).awaitKernel()
        runtime.gateway.observeDetail { detail ->
            if (detail.movie().siteKey() == site?.key) projectDetail(detail)
        }.awaitKernel()
        runtime.gateway.home(selected.key).awaitKernel().map(::projectMovie)
    }
    suspend fun search(selected: TvBoxSite, query: String): SpiderResult<List<Film>> = result {
        require(selected.key == site?.key) { "Search site differs from active kernel session" }
        runtime.gateway.search(selected.key, query).awaitKernel().map(::projectMovie)
    }
    suspend fun details(film: Film): SpiderResult<Film> = result {
        require(owns(film)) { "Kernel movie session expired" }
        projectDetail(runtime.gateway.details(requireNotNull(film.spiderVodId)).awaitKernel())
    }
    fun media(film: Film, number: Int, line: String): PlaybackResult = try {
        PlaybackResult.Ready(PlaybackMedia("kernel-selection:$line", kernelPlayback = ticket(film, number, line)))
    } catch (_: IllegalArgumentException) { PlaybackResult.Unavailable("来源会话或选集已变化，请返回详情重新选择") }
    fun latest(film: Film): Film? = film.spiderVodId?.let(latest::get)
    private fun projectMovie(movie: Kernel.Movie): Film {
        movies[movie.handle()] = movie
        return requireNotNull(authority).sign(Film("kernel-${movie.handle()}", movie.title(), "", "剧集",
            movie.year(), movie.synopsis(), R.drawable.photofilm_orbit, artworkUrl = movie.artwork().takeIf(String::isNotBlank),
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
        movies.clear(); selections.clear(); latest.clear(); site = null; authority = null
        runtime.gateway.stop()
    }
    private suspend fun <T> result(block: suspend () -> T): SpiderResult<T> = try { SpiderResult.Ready(block()) }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) { SpiderResult.Failure("原版内核请求失败；未回退旧播放系统") }
}
