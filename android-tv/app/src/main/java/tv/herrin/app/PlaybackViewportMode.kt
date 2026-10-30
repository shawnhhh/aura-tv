package tv.herrin.app

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Full-bleed TV playback without distorting the source aspect ratio.
 *
 * FILL uses center-crop: the video covers every pixel of the display,
 * including letterboxed cinematic streams, while the edge of the picture may
 * be cropped. FIT preserves the entire original picture with black bars when
 * the aspect ratio differs. Neither mode stretches faces horizontally.
 */
internal enum class PlaybackViewportMode(val label: String, val resizeMode: Int) {
    FILL("铺满（裁切）", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FIT("完整显示", AspectRatioFrameLayout.RESIZE_MODE_FIT);

    fun toggle(): PlaybackViewportMode = if (this == FILL) FIT else FILL

    companion object {
        fun fromStoredFill(fill: Boolean) = if (fill) FILL else FIT
    }
}
