package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun clearTmdbBrowseTestCache(context: Context) { File(context.filesDir, "tmdb-browse-v2").deleteRecursively() }
internal fun seedTmdbBrowseTestCache(context: Context, token: String) = runBlocking {
    val repository = TmdbBrowseRepository(TmdbBrowseFixture(), File(context.filesDir, "tmdb-browse-v2"))
    for (page in TmdbBrowsePage.entries) for (spec in TmdbRailSpec.forPage(page)) repository.load(spec, 1, token)
}
internal fun cachedTmdbBrowseTestFilms(context: Context, token: String): List<Film> = runBlocking {
    val repository = TmdbBrowseRepository(TmdbBrowseFixture().apply { offline = true }, File(context.filesDir, "tmdb-browse-v2"))
    TmdbBrowsePage.entries.flatMap { page -> TmdbRailSpec.forPage(page).flatMap {
        repository.cached(it, 1, token)?.entries?.map(TmdbBrowseEntry::film).orEmpty()
    } }.distinctBy(Film::id)
}
