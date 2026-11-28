package tv.herrin.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate

/** Keep the index projection alive across detail/player/Back; no database work per focus frame. */
@Composable
internal fun rememberPersonalBrowseFilms(revision: Long, connectionEpoch: Int): List<Film> {
    val context = LocalContext.current.applicationContext
    var films by remember { mutableStateOf<List<Film>>(emptyList()) }
    val currentRevision by rememberUpdatedState(revision)
    LaunchedEffect(context, connectionEpoch) {
        snapshotFlow { currentRevision }.conflate().collect {
        val fresh = withContext(Dispatchers.IO) {
            val library = PersonalLibrary(context)
            val enabled = library.connections.list().filter { it.enabled }.map { it.id }.toSet()
            val folders = library.index.folders().filter { it.enabled && it.connection in enabled }.map { it.id }.toSet()
            val projection = PersonalMediaProjection.Accumulator()
            val began = android.os.SystemClock.elapsedRealtime()
            var files = 0
            var after = ""
            while (true) {
                ensureActive()
                val page = library.index.mediaAfter(after)
                if (page.isEmpty()) break
                val allowed = page.filter { it.optString("folder") in folders }
                projection.add(allowed)
                files += allowed.size
                after = page.last().getString("id")
            }
            ensureActive()
            val output = projection.browseFilms()
            HerrinPlaybackDiagnosticJournal.record("PERSONAL", "PROJECTION_ELAPSED", "MS=${android.os.SystemClock.elapsedRealtime() - began}")
            HerrinPlaybackDiagnosticJournal.record("PERSONAL", "PROJECTION_FILES", "COUNT=$files")
            output
        }
        films = fresh
        }
    }
    return films
}
