package tv.herrin.app

import androidx.media3.ui.AspectRatioFrameLayout

/** The entire decoded frame is visible. Encoded black bars are content, never cropped. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal enum class PlaybackViewportMode(val label: String, val resizeMode: Int) {
    FIT("完整显示（等比例）", AspectRatioFrameLayout.RESIZE_MODE_FIT);

    companion object {
        /** Old versions persisted true by default; that must not silently crop an upgrade. */
        @Suppress("UNUSED_PARAMETER")
        fun fromStoredFill(fill: Boolean) = FIT
    }
}
