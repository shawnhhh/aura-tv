package tv.herrin.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class StartupPresentation {
    var showing by mutableStateOf(true)
    var exiting by mutableStateOf(false)
    var homeReady by mutableStateOf(false)
    var prepareHome by mutableStateOf(false)
    var reduced by mutableStateOf(false)
    var preferences by mutableStateOf<Preferences?>(null)
}

/** Paint branding before constructing the large home composition and its repositories. */
@Composable
internal fun StartupHost(home: @Composable (StartupPresentation) -> Unit) {
    val state = remember { StartupPresentation() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        state.preferences = withContext(Dispatchers.IO) {
            CrashJournal.loadPrevious(context)
            // Keystore migration/decryption must not occupy the first input frame.
            Preferences(context).apply { tmdbReadToken }
        }
        while (true) { withContext(Dispatchers.IO) { CrashJournal.sampleMemory(); CrashJournal.checkpoint(context) }; delay(5000) }
    }
    Box(Modifier.fillMaxSize().drawWithContent { drawContent(); StartupTiming.firstDraw() }) {
        if (state.prepareHome && state.preferences != null) home(state)
        if (state.showing) SplashExitCurtain(state.reduced, state.exiting,
            onReady = { state.exiting = true },
            onFinished = { state.showing = false }, homeReady = state.homeReady,
            onVisible = { state.prepareHome = true })
    }
}
