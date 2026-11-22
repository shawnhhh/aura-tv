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
internal fun PersonalLibraryIndexChanges(revision: Long, screen: String, state: TmdbScreenState) {
    val guard = remember(state) { PersonalIndexRevisionGuard(revision) }
    LaunchedEffect(revision, screen) {
        if(guard.advance(revision, screen)) {
            state.tmdbPlaybackRoutes.clear()
            state.tmdbFallbackIndex.clear()
            state.tmdbLookupEpoch++
        }
    }
}
