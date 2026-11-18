package tv.herrin.app

import androidx.compose.runtime.*

/** Do not revoke a running player when scanning changes only index data. */
internal class PersonalIndexRevisionGuard(private var applied: Long) {
    fun advance(revision: Long, playing: Boolean): Boolean {
        if(playing || revision <= applied) return false
        applied = revision
        return true
    }
}

@Composable
internal fun PersonalLibraryIndexChanges(revision: Long, screen: String, state: TmdbScreenState) {
    val guard = remember(state) { PersonalIndexRevisionGuard(revision) }
    LaunchedEffect(revision, screen) {
        if(guard.advance(revision, screen == "player")) {
            state.tmdbPlaybackRoutes.clear()
            state.tmdbFallbackIndex.clear()
            state.tmdbLookupEpoch++
        }
    }
}
