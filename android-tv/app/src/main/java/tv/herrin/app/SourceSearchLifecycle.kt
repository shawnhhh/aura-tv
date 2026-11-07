package tv.herrin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Stop untrusted discovery work when the detail's Activity leaves the foreground.
 * This observes lifecycle, not window focus: dialogs do not revoke a search. */
@Composable
internal fun SourceSearchLifecycle(state:TmdbScreenState) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,state) {
        fun update() {
            val started=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if(!started) {
                // Stop/Start can happen before Compose sees a frame. Keep an
                // observable epoch so a cancelled effect is restarted even then.
                if(state.sourceForeground)state.tmdbLookupEpoch++
                state.tmdbSearchGeneration.incrementAndGet()
                state.sourceSearchJob?.cancel()
            }
            state.sourceForeground=started
        }
        val observer=LifecycleEventObserver { _,_-> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            state.tmdbSearchGeneration.incrementAndGet()
            state.sourceSearchJob?.cancel()
            state.sourceForeground=false
        }
    }
}
