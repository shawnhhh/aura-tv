package tv.herrin.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keep the index projection alive across detail/player/Back; no database work per focus frame. */
@Composable
internal fun rememberPersonalBrowseFilms(revision: Long, connectionEpoch: Int): List<Film> {
    val context = LocalContext.current.applicationContext
    var films by remember { mutableStateOf<List<Film>>(emptyList()) }
    LaunchedEffect(context, revision, connectionEpoch) {
        val fresh = withContext(Dispatchers.IO) {
            val library = PersonalLibrary(context)
            val enabled = library.connections.list().filter { it.enabled }.map { it.id }.toSet()
            val folders = library.index.folders().filter { it.enabled && it.connection in enabled }.map { it.id }.toSet()
            val rows = mutableListOf<org.json.JSONObject>()
            var after = ""
            while (true) {
                ensureActive()
                val page = library.index.mediaAfter(after)
                if (page.isEmpty()) break
                rows += page.filter { it.optString("folder") in folders }
                after = page.last().getString("id")
            }
            ensureActive()
            PersonalMediaProjection.films(rows)
        }
        films = fresh // A cancelled revision cannot publish over a newer one.
    }
    return films
}
