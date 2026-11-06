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
}

/** Paint branding before constructing the large home composition and its repositories. */
@Composable
internal fun StartupHost(home: @Composable (StartupPresentation) -> Unit) {
    val state = remember { StartupPresentation() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            CrashJournal.loadPrevious(context)
            // Prewarm the existing preferences before the home accesses them.
            context.getSharedPreferences("herrin", 0).all
        }
        while (true) { withContext(Dispatchers.Default) { CrashJournal.sampleMemory() }; delay(5000) }
    }
    Box(Modifier.fillMaxSize().drawWithContent { drawContent(); StartupTiming.firstDraw() }) {
        if (state.prepareHome) home(state)
        if (state.showing) SplashExitCurtain(state.reduced, state.exiting,
            onReady = { state.exiting = true },
            onFinished = { state.showing = false }, homeReady = state.homeReady,
            onVisible = { state.prepareHome = true })
    }
}
