package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import androidx.compose.ui.platform.LocalContext

/** Incremental scans reuse the persistent index and only previously imported enabled roots. */
@Composable
internal fun PersonalLibraryStartupImport() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(context) {
        val library = withContext(Dispatchers.IO) { PersonalLibrary(context) }
        while (isActive) {
        val pending = withContext(Dispatchers.IO) {
            val enabled = library.connections.list().filter { it.enabled }.map { it.id }.toSet()
            library.index.folders().filter { it.enabled && it.connection in enabled &&
                PersonalSyncPolicy.due(it.scanned, System.currentTimeMillis()) }
        }
        for(folder in pending) {
            ensureActive()
            val started = withContext(Dispatchers.IO) {
                runCatching { library.refresh(folder.id) }.isSuccess
            }
            if(!started) continue // An explicit user import/refresh already owns the scanner.
            if(!PersonalLibrary.awaitCurrentScan()) break // User cancellation also stops queued legacy imports.
        }
        delay(PersonalSyncPolicy.INTERVAL_MS)
        }
    }
}
