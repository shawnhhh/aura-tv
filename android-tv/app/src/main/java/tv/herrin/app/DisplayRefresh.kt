package tv.herrin.app

import android.app.Activity
import android.util.Log

internal data class UiDisplayMode(val width: Int, val height: Int, val hz: Float)

/** Prefer high refresh only at the current output resolution; never drop 4K to get 120 Hz. */
internal fun preferredUiRefreshRate(current: UiDisplayMode, supported: List<UiDisplayMode>): Float? =
    supported.asSequence()
        .filter { it.width == current.width && it.height == current.height }
        .map { it.hz }
        .filter { it >= 90f && it <= 120.5f }
        .maxOrNull()

/** A refresh-rate hint, not a guarantee: the TV firmware ultimately selects the mode. */
internal fun Activity.preferSmoothTvUi() {
    @Suppress("DEPRECATION")
    val display = windowManager.defaultDisplay
    val active = display.mode
    val current = UiDisplayMode(active.physicalWidth, active.physicalHeight, active.refreshRate)
    val supported = display.supportedModes.map { mode ->
        UiDisplayMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }
    val preference = preferredUiRefreshRate(current, supported)
    if (preference != null) {
        val params = window.attributes
        params.preferredRefreshRate = preference
        window.attributes = params
    }
    Log.i("HerrinDisplay", "output=${current.width}x${current.height}@${current.hz}Hz " +
        "uiPreference=${preference ?: "system"} supported=${supported.joinToString()}")
}
