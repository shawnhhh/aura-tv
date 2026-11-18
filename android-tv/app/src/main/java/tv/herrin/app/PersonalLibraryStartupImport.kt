package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import androidx.compose.ui.platform.LocalContext

/** Previously authorized legacy roots need their first index before normal index-only lookups. */
@Composable
internal fun PersonalLibraryStartupImport(onImported: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val completed by rememberUpdatedState(onImported)
    LaunchedEffect(context) {
        val library = withContext(Dispatchers.IO) { PersonalLibrary(context) }
        val pending = withContext(Dispatchers.IO) {
            val enabled = library.connections.list().filter { it.enabled }.map { it.id }.toSet()
            library.index.folders().filter { it.enabled && it.connection in enabled && it.state == "pending" && it.scanned == 0L }
        }
        for(folder in pending) {
            ensureActive()
            val started = withContext(Dispatchers.IO) {
                runCatching { library.refresh(folder.id) }.isSuccess
            }
            if(!started) continue // An explicit user import/refresh already owns the scanner.
            if(!PersonalLibrary.awaitCurrentScan()) break // User cancellation also stops queued legacy imports.
            completed()
        }
    }
}
