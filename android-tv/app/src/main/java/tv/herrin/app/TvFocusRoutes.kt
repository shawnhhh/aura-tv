package tv.herrin.app

/** Stable D-pad order for the intentionally compact, two-source TV settings page. */
internal object TvFocusRoutes {
    fun compactSettingsNeighbor(current: String, down: Boolean, movieAction: String): String? =
        when (current) {
            "screen-first" -> if (down) "tvbox-warehouse-url" else null
            "tvbox-warehouse-url" -> if (down) "tvbox-warehouse-load" else "screen-first"
            "tvbox-warehouse-load", "settings-source-sites" ->
                if (down) "tvbox-live-direct-url" else "tvbox-warehouse-url"
            "tvbox-live-direct-url" ->
                if (down) "tvbox-live-direct-load" else
                    movieAction.takeIf { it == "settings-source-sites" } ?: "tvbox-warehouse-load"
            "tvbox-live-direct-load" -> if (down) "settings-home" else "tvbox-live-direct-url"
            "settings-home" -> if (down) "settings-advanced" else "tvbox-live-direct-load"
            "settings-advanced" -> if (down) null else "settings-home"
            else -> null
        }

    /** LazyColumn indices include the non-focusable section headers. */
    fun compactSettingsItemIndex(id: String): Int? = when (id) {
        "screen-first" -> 0
        "tvbox-warehouse-url" -> 3
        "tvbox-warehouse-load", "settings-source-sites" -> 4
        "tvbox-live-direct-url" -> 6
        "tvbox-live-direct-load" -> 7
        "settings-home" -> 8
        "settings-advanced" -> 9
        else -> null
    }
}
