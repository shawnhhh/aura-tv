package tv.herrin.app

import androidx.compose.runtime.*

/** Index-only updates must not revoke a selected detail or a pending/running playback transaction. */
internal class PersonalIndexRevisionGuard(private var applied: Long) {
    fun advance(revision: Long, screen: String): Boolean {
        if(screen in setOf("detail", "play-intent", "player") || revision <= applied) return false
        applied = revision
        return true
    }
}

@Composable
internal fun PersonalLibraryIndexChanges(revision: Long, screen: String, state: TmdbScreenState,
    currentScreen: () -> String = { screen }) {
    val guard = remember(state) { PersonalIndexRevisionGuard(revision) }
    val latestScreen by rememberUpdatedState(currentScreen)
    LaunchedEffect(revision, screen) {
        // A click may have begun playback after this old browse composition
        // scheduled its effect. Read the live scene before revoking routes.
        if(guard.advance(revision, latestScreen())) {
            state.tmdbPlaybackRoutes.clear()
            state.tmdbFallbackIndex.clear()
            state.tmdbLookupEpoch++
        }
    }
}
