package tv.herrin.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class StartupPresentation {
    var diagnostics = false
    // Activity owns this transient callback; no cache/network work on window focus.
    var restoreBrowseFocus:(()->Boolean)?=null
    var showing by mutableStateOf(true)
    var exiting by mutableStateOf(false)
    var homeReady by mutableStateOf(false)
    var prepareHome by mutableStateOf(false)
    var reduced by mutableStateOf(false)
    var preferences by mutableStateOf<Preferences?>(null)
    var profiles:CatalogProfiles?=null
    var initialProfiles:List<CatalogProfile> = emptyList()
    var initialProfileId:String?=null
    var artworkReady by mutableStateOf(false)
    var networkReady by mutableStateOf(false)
    var wordmarkWidthDp by mutableFloatStateOf(420f)
}

/** Paint branding before constructing the large home composition and its repositories. */
@Composable
internal fun StartupHost(initial: StartupPresentation? = null, home: @Composable (StartupPresentation) -> Unit) {
    val state = remember { initial ?: StartupPresentation() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        state.preferences = withContext(Dispatchers.IO) { coroutineScope {
            val housekeeping = async { runCatching {
                prepareStartupText()
                CrashJournal.loadPrevious(context)
                context.getSharedPreferences("herrin-watch-history", 0).all
            } }
            val profiles = async {
                CatalogProfiles(context).also {
                    state.profiles=it; state.initialProfiles=it.list(); state.initialProfileId=it.activeId()
                }
            }
            val preferences = async {
                Preferences(context).apply {
                    tmdbReadToken
                    // Some Android TV firmware sets the global animation scale
                    // to zero by default, unintentionally replacing V13 with a
                    // static wordmark. Honor the explicit Herrin preference only.
                    state.reduced = reducedMotion
                }
            }
            profiles.await()
            // Diagnostics are independent of the minimum interactive home state.
            // A diagnostic read failure must not strand the startup curtain.
            runCatching { housekeeping.await() }
            preferences.await()
        } }

        while (true) { withContext(Dispatchers.IO) { CrashJournal.sampleMemory(); CrashJournal.checkpoint(context) }; delay(5000) }
    }
    LaunchedEffect(state.prepareHome, state.preferences) {
        if (state.prepareHome && state.preferences != null) {
            // onVisible admits home construction after the branding layer is
            // mounted. Yield a frame before releasing its network effects;
            // waiting for showing=false would serialize all I/O after splash.
            withFrameNanos { }
            state.networkReady = true
            StartupTiming.networkStart()
        }
    }
    LaunchedEffect(state.prepareHome,state.preferences) {
        if (state.prepareHome && state.preferences!=null) {
            // Warm artwork after full reveal, during the independent breath.
            // The underlay must already exist when the curtain starts fading.
            withContext(Dispatchers.IO) {
                runCatching {
                    DemoCatalog().titles().take(2).forEach {
                        BundledArtworkCache.load(context, it.artwork)
                    }
                }
            }
            state.artworkReady=true;StartupTiming.artworkStart()
        }
    }
    Box(Modifier.fillMaxSize().drawWithContent { StartupTiming.firstDraw(); drawContent() }) {
        if (state.prepareHome && state.preferences != null) {
            StartupTiming.homeStart()
            CompositionLocalProvider(LocalArtworkReady provides state.artworkReady) { home(state) }
        }
        if (state.showing) SplashExitCurtain(state.reduced, state.exiting,
            onReady = { state.exiting = true },
            onFinished = { state.showing = false }, homeReady = state.homeReady,
            onVisible = { state.prepareHome = true },wordmarkWidthDp=state.wordmarkWidthDp)
    }
}
