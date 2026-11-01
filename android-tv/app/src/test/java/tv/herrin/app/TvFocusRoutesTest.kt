package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusRoutesTest {
    @Test fun compactSettingsHasReversibleVerticalPathIncludingTmdbEditor() {
        val ids = listOf("screen-first", "tvbox-warehouse-url", "tvbox-warehouse-load",
            "compact-tmdb-split-toggle", "compact-tmdb-token", "compact-tmdb-save",
            "tvbox-live-direct-url", "tvbox-live-direct-load", "settings-home",
            "settings-advanced")
        ids.zipWithNext().forEach { (above, below) ->
            assertEquals(below, TvFocusRoutes.compactSettingsNeighbor(
                above, true, "tvbox-warehouse-load"))
            assertEquals(above, TvFocusRoutes.compactSettingsNeighbor(
                below, false, "tvbox-warehouse-load"))
        }
        assertNull(TvFocusRoutes.compactSettingsNeighbor(
            "screen-first", false, "tvbox-warehouse-load"))
        assertNull(TvFocusRoutes.compactSettingsNeighbor(
            "settings-advanced", true, "tvbox-warehouse-load"))
    }

    @Test fun splitTokenNavigationReachesAllThreeFieldsAndSave() {
        val ids = listOf("compact-tmdb-split-toggle", "compact-tmdb-part-1",
            "compact-tmdb-part-2", "compact-tmdb-part-3", "compact-tmdb-save")
        ids.zipWithNext().forEach { (above, below) ->
            assertEquals(below, TvFocusRoutes.compactSettingsNeighbor(
                above, true, "tvbox-warehouse-load", splitToken = true))
            assertEquals(above, TvFocusRoutes.compactSettingsNeighbor(
                below, false, "tvbox-warehouse-load", splitToken = true))
        }
    }

    @Test fun movieSiteButtonRetainsColumnReturningFromTmdbAndDynamicLazyIndex() {
        assertEquals("compact-tmdb-split-toggle", TvFocusRoutes.compactSettingsNeighbor(
            "settings-source-sites", true, "settings-source-sites"))
        assertEquals("settings-source-sites", TvFocusRoutes.compactSettingsNeighbor(
            "compact-tmdb-split-toggle", false, "settings-source-sites"))
        assertEquals(4, TvFocusRoutes.compactSettingsItemIndex("settings-source-sites"))
        assertEquals(7, TvFocusRoutes.compactSettingsItemIndex("compact-tmdb-token"))
        assertEquals(8, TvFocusRoutes.compactSettingsItemIndex("compact-tmdb-save"))
        assertEquals(11, TvFocusRoutes.compactSettingsItemIndex("tvbox-live-direct-url"))
        assertEquals(14, TvFocusRoutes.compactSettingsItemIndex("settings-advanced"))
        assertEquals(8, TvFocusRoutes.compactSettingsItemIndex(
            "compact-tmdb-token", sourceStatus = true))
        assertEquals(14, TvFocusRoutes.compactSettingsItemIndex(
            "tvbox-live-direct-url", sourceStatus = true,
            connectedStatus = true, failedStatus = true))
        assertEquals(15, TvFocusRoutes.compactSettingsItemIndex(
            "tvbox-live-direct-url", sourceStatus = true,
            connectedStatus = true, failedStatus = true, noMatchesStatus = true))
    }
}
