package tv.herrin.app

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TmdbRailState(
    val entries: List<TmdbBrowseEntry> = emptyList(), val page: Int = 0,
    val hasMore: Boolean = true, val loading: Boolean = false, val error: Boolean = false, val unknown: Int = 0
)

/** Each rail owns its page/error/cache. Focused rails publish refreshes only after focus leaves. */
@Stable
internal class TmdbBrowseState(val repository: TmdbBrowseRepository, val token: String) {
    val rows = mutableStateMapOf<String, TmdbRailState>()
    val scrolls = mutableMapOf<String, LazyListState>()
    // A cached row entry is NOT necessarily a composed LazyRow poster.
    // Mark only truly laid-out poster controls as safe explicit D-pad targets.
    val attachedPosters = mutableStateMapOf<String, Boolean>()
    fun isPosterAttached(tag: String): Boolean = attachedPosters[tag] == true
    private val pending = mutableMapOf<String, TmdbBrowseBatch>()
    private val failedPages = mutableMapOf<String, Int>()
    private val loads = mutableMapOf<String, Mutex>()
    private var focused: String? = null
    fun row(spec: TmdbRailSpec) = rows[spec.key] ?: TmdbRailState()
    fun films(page: TmdbBrowsePage) = TmdbRailSpec.forPage(page).flatMap { row(it).entries.map(TmdbBrowseEntry::film) }.distinctBy(Film::id)
    fun allFilms() = TmdbBrowsePage.entries.flatMap(::films).distinctBy(Film::id)
    fun focus(key: String?) {
        if (focused != key) {
            focused?.let { old -> pending.remove(old)?.let { batch ->
                // A deferred first-page refresh must not erase pages loaded while focused.
                val current = rows[old] ?: TmdbRailState()
                publish(old, batch)
                if (current.page > 1) rows[old] = rows.getValue(old).copy(
                    entries = (batch.entries + current.entries).distinctBy { it.film.id },
                    page = current.page, hasMore = current.hasMore, error = current.error, loading = current.loading)
            } }
            focused = key
        }
    }
    private fun publish(key: String, batch: TmdbBrowseBatch) {
        val old = rows[key] ?: TmdbRailState()
        val combined = if (batch.page == 1) batch.entries else (old.entries + batch.entries).distinctBy { it.film.id }
        val entries = if (key.endsWith("-latest")) combined.sortedByDescending { it.availability.date } else combined
        rows[key] = old.copy(entries = entries, page = batch.page, hasMore = batch.hasMore,
            unknown = batch.unknown, loading = false, error = false)
    }
    suspend fun hydrate(onlyPage: TmdbBrowsePage? = null) {
        // Decode all bounded first-page caches off main, then publish once. Publishing
        // after each IO suspension used to recompose the whole home up to 22 times.
        val restored = withContext(Dispatchers.IO) {
            buildMap<String, TmdbRailState> {
                for (page in onlyPage?.let { listOf(it) } ?: TmdbBrowsePage.entries) for (spec in TmdbRailSpec.forPage(page)) {
                    val cached = try { repository.cached(spec, 1, token) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    if (cached != null) put(spec.key, TmdbRailState(cached.entries, cached.page,
                        cached.hasMore, unknown = cached.unknown))
                }
            }
        }
        rows.putAll(restored.filterKeys { (rows[it]?.page ?: 0) == 0 })
    }
    suspend fun refresh(page: TmdbBrowsePage, force: Boolean = false) = coroutineScope {
        if (token.isBlank()) return@coroutineScope
        TmdbRailSpec.forPage(page).map { spec -> launch { load(spec, 1, force) } }.joinAll()

    }
    suspend fun more(spec: TmdbRailSpec) {
        val current = row(spec)
        if (!current.loading && current.hasMore) load(spec, (current.page + 1).coerceAtLeast(1), false)
    }
    suspend fun retry(spec: TmdbRailSpec) = load(spec, failedPages[spec.key] ?: if (row(spec).page == 0) 1 else row(spec).page, true)
    private suspend fun load(spec: TmdbRailSpec, page: Int, force: Boolean) = loads.getOrPut(spec.key) { Mutex() }.withLock {
        // A replacement LaunchedEffect can start before the cancelled owner's
        // finally/catch finishes. Wait for that owner instead of dropping refresh.
        currentCoroutineContext().ensureActive()
        if (page > 1 && !force && row(spec).page >= page) return@withLock
        rows[spec.key] = row(spec).copy(loading = true, error = false)
        val publisher = currentCoroutineContext()
        try {
            val batch = repository.load(spec, page, token, force) { preview ->
                withContext(publisher) {
                    // Cached or focused content stays in place during refresh.
                    if (row(spec).entries.isEmpty()) {
                        publish(spec.key, preview)
                        rows[spec.key] = row(spec).copy(loading = true)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            failedPages.remove(spec.key)
            if (page == 1 && focused == spec.key && row(spec).entries.isNotEmpty()) {
                pending[spec.key] = batch
                rows[spec.key] = row(spec).copy(loading = false)
            } else publish(spec.key, batch)
        } catch (cancel: CancellationException) {
            rows[spec.key] = row(spec).copy(loading = false)
            throw cancel
        } catch (_: Exception) {
            failedPages[spec.key] = page
            rows[spec.key] = row(spec).copy(loading = false, error = true)
        }
    }
}
