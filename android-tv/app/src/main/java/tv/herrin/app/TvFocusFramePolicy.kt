package tv.herrin.app

/**
 * Remote navigation must update focus immediately while expensive full-screen
 * background transitions follow only after the cursor has settled.
 *
 * Short dwell suppresses abandoned hero image decoding/crossfades when users
 * quickly traverse many posters; it does NOT delay Enter, metadata, or playback.
 */
internal object TvFocusFramePolicy {
    const val HERO_DWELL_MS = 200L
    const val HERO_CROSSFADE_MS = 170
    const val HERO_ARTWORK_DOWNLOAD_DWELL_MS = 110L

    fun delaysBackground(screen: String): Boolean =
        screen == "browse" || screen == "search"

    fun allowsBackdrop(screen: String): Boolean =
        screen != "player" && screen != "live-player"

    fun shouldLoadSeasonDetails(screen: String): Boolean = screen == "detail"

    fun needsPlaybackSelectionData(screen: String): Boolean =
        screen == "detail" || screen == "player"
}
