package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusRoutesTest {
    @Test fun compactSettingsHasReversibleVerticalPath() {
        val ids = listOf("screen-first", "tvbox-warehouse-url", "tvbox-warehouse-load",
            "tvbox-live-direct-url", "tvbox-live-direct-load", "settings-home", "settings-advanced")
        ids.zipWithNext().forEach { (above, below) ->
            assertEquals(below, TvFocusRoutes.compactSettingsNeighbor(above, true, "tvbox-warehouse-load"))
            assertEquals(above, TvFocusRoutes.compactSettingsNeighbor(below, false, "tvbox-warehouse-load"))
        }
        assertNull(TvFocusRoutes.compactSettingsNeighbor("screen-first", false, "tvbox-warehouse-load"))
        assertNull(TvFocusRoutes.compactSettingsNeighbor("settings-advanced", true, "tvbox-warehouse-load"))
    }

    @Test fun movieSiteButtonRetainsColumnOnReturnFromLiveInput() {
        assertEquals("tvbox-live-direct-url", TvFocusRoutes.compactSettingsNeighbor(
            "settings-source-sites", true, "settings-source-sites"))
        assertEquals("settings-source-sites", TvFocusRoutes.compactSettingsNeighbor(
            "tvbox-live-direct-url", false, "settings-source-sites"))
        assertEquals(4, TvFocusRoutes.compactSettingsItemIndex("settings-source-sites"))
    }
}
