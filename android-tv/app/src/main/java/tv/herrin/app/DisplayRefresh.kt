package tv.herrin.app

import android.app.Activity
import android.util.Log

internal data class UiDisplayMode(val width: Int, val height: Int, val hz: Float)

/** Prefer high refresh only at the current output resolution; never drop 4K to get 120 Hz. */
internal fun preferredUiRefreshRate(current: UiDisplayMode, supported: List<UiDisplayMode>, playback: Boolean = false): Float? =
    if (playback) null else
    supported.asSequence()
        .filter { it.width == current.width && it.height == current.height }
        .map { it.hz }
        .filter { it.isFinite() && it >= 90f && it >= current.hz }
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
    if (preference != null && preference > current.hz + 0.5f &&
        kotlin.math.abs(window.attributes.preferredRefreshRate - preference) > 0.5f) {
        val params = window.attributes
        params.preferredRefreshRate = preference
        // Pin geometry as well as rate: firmware must never choose a lower resolution.
        params.preferredDisplayModeId = display.supportedModes.first {
            it.physicalWidth == current.width && it.physicalHeight == current.height &&
                kotlin.math.abs(it.refreshRate - preference) < 0.01f
        }.modeId
        window.attributes = params
    }
    Log.i("HerrinDisplay", "output=${current.width}x${current.height}@${current.hz}Hz " +
        "uiPreference=${preference ?: "system"} supported=${supported.joinToString()}")
}

/** Yield window hints on a scene boundary so the video surface/core owns frame matching. */
internal fun Activity.releaseUiRefreshPreference() {
    val params = window.attributes
    if (params.preferredRefreshRate != 0f || params.preferredDisplayModeId != 0) {
        params.preferredRefreshRate = 0f
        params.preferredDisplayModeId = 0
        window.attributes = params
        Log.i("HerrinDisplay", "scene=playback uiPreference=system; video frame matching owns cadence")
    }
}
