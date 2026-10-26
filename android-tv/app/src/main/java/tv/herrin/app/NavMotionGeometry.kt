package tv.herrin.app

/** Pixel-space bounds used only for drawing the non-interactive navigation focus track. */
internal data class NavPillBounds(val leftPx: Float, val widthPx: Float)

/** Keep the luminous track centered beneath its actual, measured D-pad target. */
internal fun navTrackBounds(pill: NavPillBounds): NavPillBounds {
    val available = pill.widthPx.coerceAtLeast(0f)
    val width = (available * 0.62f).coerceAtLeast(1f)
    return NavPillBounds(pill.leftPx + ((available - width).coerceAtLeast(0f) / 2f), width)
}
