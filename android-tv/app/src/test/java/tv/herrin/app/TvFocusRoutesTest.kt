package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusRoutesTest {
    @Test fun rawTop250MoviesCannotShareTheCollapsedShowKey() {
        val first = Film("tmdb-movie-1", "原创排行 第1季", "", "动漫", "2026", "", 1)
        val second = first.copy(id = "tmdb-movie-2", title = "原创排行 第2季")
        val groups = ShowCatalog.build(listOf(first, second), "original-test")
        val groupKey = groups.groupFor(first.id)!!.key
        assertEquals(groupKey, groups.groupFor(second.id)!!.key)
        val keys = listOf(first, second).map {
            TvFocusRoutes.posterKey("Top 250", true, it.id, groups.groupFor(it.id)?.key)
        }
        assertEquals(listOf(first.id, second.id), keys)
        assertEquals(groupKey, TvFocusRoutes.posterKey("动漫", true, first.id, groupKey))
    }

    @Test fun top250PostersReturnToTheirOwnAttachedNavigationTab() {
        for (index in listOf(0, 1, 10, 49))
            assertEquals("nav-Top 250", TvFocusRoutes.posterUp("Top 250", index, true))
    }

    @Test fun personalLibraryPostersDoNotTargetAbsentHeroButtons() {
        for (index in listOf(0, 1, 10, 49))
            assertEquals("my-manage-personal-sources", TvFocusRoutes.posterUp("我的", index, true))
    }

    @Test fun sourceOnlyEmptyCatalogHistoryReturnsToNavigation() {
        for (index in listOf(0, 1, 10))
            assertEquals("nav-首页", TvFocusRoutes.posterUp("首页", index, false))
    }

    @Test fun ordinaryMovieHeroRetainsItsPlayAndDetailsNavigation() {
        assertEquals("play", TvFocusRoutes.posterUp("电影", 0, true))
        assertEquals("details", TvFocusRoutes.posterUp("电影", 9, true))
        assertEquals("play", TvFocusRoutes.posterUp("首页", 0, true))
    }

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
        assertEquals(25, TvFocusRoutes.compactSettingsItemIndex("tvbox-live-direct-url"))
        assertEquals(28, TvFocusRoutes.compactSettingsItemIndex("settings-advanced"))
        assertEquals(8, TvFocusRoutes.compactSettingsItemIndex(
            "compact-tmdb-token", sourceStatus = true))
        assertEquals(28, TvFocusRoutes.compactSettingsItemIndex(
            "tvbox-live-direct-url", sourceStatus = true,
            connectedStatus = true, failedStatus = true))
        assertEquals(29, TvFocusRoutes.compactSettingsItemIndex(
            "tvbox-live-direct-url", sourceStatus = true,
            connectedStatus = true, failedStatus = true, noMatchesStatus = true))
    }
}
