package tv.herrin.app

/**
 * Retains the latest desired vertical destination while a compact-settings
 * LazyColumn is attaching an offscreen row.
 *
 * A rapid sequence of DOWN/DOWN/DOWN must count as three distinct moves even
 * if the Compose focus callback for the first move is still waiting for a
 * layout pass. This is an input queue of destinations, not delayed key events.
 * No UI recomposition or background work is needed to track its state.
 */
internal class CompactSettingsIntent {
    private var desiredFocus: String? = null

    fun next(actualFocus: String, down: Boolean, movieAction: String): String? {
        val base = desiredFocus ?: actualFocus
        val target = TvFocusRoutes.compactSettingsNeighbor(base, down, movieAction)
        if (target != null) desiredFocus = target
        return target
    }

    /** Also cover navigation started inside a TV-safe non-editing source field. */
    fun request(target: String) {
        if (TvFocusRoutes.compactSettingsItemIndex(target) != null)
            desiredFocus = target
    }

    /** Do not clear a newer destination when an older callback arrives late. */
    fun settled(actualFocus: String) {
        if (desiredFocus == actualFocus) desiredFocus = null
    }

    fun abandoned(target: String) {
        if (desiredFocus == target) desiredFocus = null
    }

    fun clear() {
        desiredFocus = null
    }
}
