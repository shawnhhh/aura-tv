package tv.herrin.app

/** D-pad navigation over TV-safe controls, including offscreen TMDB fields. */
internal object TvFocusRoutes {
    fun compactSettingsNeighbor(
        current: String, down: Boolean, movieAction: String,
        splitToken: Boolean = false
    ): String? = when (current) {
        "screen-first" -> if (down) "tvbox-warehouse-url" else null
        "tvbox-warehouse-url" -> if (down) "tvbox-warehouse-load" else "screen-first"
        "tvbox-warehouse-load", "settings-source-sites" ->
            if (down) "compact-tmdb-split-toggle" else "tvbox-warehouse-url"
        "compact-tmdb-split-toggle" ->
            if (down) if (splitToken) "compact-tmdb-part-1" else "compact-tmdb-token"
            else movieAction.takeIf { it == "settings-source-sites" } ?: "tvbox-warehouse-load"
        "compact-tmdb-token" -> if (down) "compact-tmdb-save" else "compact-tmdb-split-toggle"
        "compact-tmdb-part-1" ->
            if (down) "compact-tmdb-part-2" else "compact-tmdb-split-toggle"
        "compact-tmdb-part-2" ->
            if (down) "compact-tmdb-part-3" else "compact-tmdb-part-1"
        "compact-tmdb-part-3" ->
            if (down) "compact-tmdb-save" else "compact-tmdb-part-2"
        "compact-tmdb-save", "compact-tmdb-rescan" ->
            if (down) "tvbox-live-direct-url"
            else if (splitToken) "compact-tmdb-part-3" else "compact-tmdb-token"
        "tvbox-live-direct-url" ->
            if (down) "tvbox-live-direct-load" else "compact-tmdb-save"
        "tvbox-live-direct-load" -> if (down) "settings-home" else "tvbox-live-direct-url"
        "settings-home" -> if (down) "settings-advanced" else "tvbox-live-direct-load"
        "settings-advanced" -> if (down) null else "settings-home"
        else -> null
    }

    /**
     * LazyColumn positions include headings and optional status rows.
     * Resolve them from the current actual composition, not stale hard-coded
     * indices. The Token fields share a single LazyColumn item.
     */
    fun compactSettingsItemIndex(
        id: String, sourceStatus: Boolean = false,
        connectedStatus: Boolean = false, failedStatus: Boolean = false,
        noMatchesStatus: Boolean = false
    ): Int? {
        val tmdbHeading = 5 + if (sourceStatus) 1 else 0
        val tmdbForm = tmdbHeading + 2
        val tmdbAction = tmdbHeading + 3
        // Fourteen compact NAS/WebDAV, cloud/WebDAV and IMDb rows are placed
        // between TMDB controls and live input. LazyColumn indices must include
        // them or D-pad navigation will focus a detached, off-screen item.
        val personalSourceItems = 14
        val liveHeading = tmdbHeading + 5 + personalSourceItems +
            (if (connectedStatus) 1 else 0) +
            (if (failedStatus) 1 else 0) +
            (if (noMatchesStatus) 1 else 0)
        return when (id) {
            "screen-first" -> 0
            "tvbox-warehouse-url" -> 3
            "tvbox-warehouse-load", "settings-source-sites" -> 4
            "compact-tmdb-split-toggle", "compact-tmdb-token",
            "compact-tmdb-part-1", "compact-tmdb-part-2", "compact-tmdb-part-3" -> tmdbForm
            "compact-tmdb-save", "compact-tmdb-rescan" -> tmdbAction
            "tvbox-live-direct-url" -> liveHeading + 1
            "tvbox-live-direct-load" -> liveHeading + 2
            "settings-home" -> liveHeading + 3
            "settings-advanced" -> liveHeading + 4
            else -> null
        }
    }
}
