package tv.herrin.app

/** Keep network-heavy bulk matching bounded while allowing focused outliers. */
internal object ArtworkScrapePolicy {
    const val STARTUP_LIMIT = 240
    const val FOCUS_DWELL_MS = 400L

    fun shouldMatchFocused(index: Int, alreadyMatched: Boolean): Boolean =
        index >= STARTUP_LIMIT && !alreadyMatched
}
