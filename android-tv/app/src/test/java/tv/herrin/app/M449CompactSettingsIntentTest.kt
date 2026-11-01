package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M449CompactSettingsIntentTest {
    @Test fun fourDownKeysReachTokenWithoutWaitingForFocusCallback() {
        val navigation = CompactSettingsIntent()
        val commands = List(4) {
            navigation.next("screen-first", down = true, movieAction = "tvbox-warehouse-load")
        }
        assertEquals(listOf("tvbox-warehouse-url", "tvbox-warehouse-load",
            "compact-tmdb-split-toggle", "compact-tmdb-token"), commands)
        navigation.settled("compact-tmdb-token")
        assertEquals("compact-tmdb-save",
            navigation.next("compact-tmdb-token", true, "tvbox-warehouse-load"))
    }

    @Test fun reversalsPreserveLatestRequestedDirectionRatherThanLastActualFocus() {
        val navigation = CompactSettingsIntent()
        assertEquals("tvbox-warehouse-url",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        assertEquals("tvbox-warehouse-load",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        navigation.settled("tvbox-warehouse-url")
        assertEquals("tvbox-warehouse-url",
            navigation.next("screen-first", false, "tvbox-warehouse-load"))
        assertEquals("tvbox-warehouse-load",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
    }

    @Test fun boundStopsDoNotWrapAndCancelledDestinationIsNotKeptForever() {
        val navigation = CompactSettingsIntent()
        assertNull(navigation.next("screen-first", false, "tvbox-warehouse-load"))
        navigation.request("settings-home")
        assertEquals("settings-advanced",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        assertNull(navigation.next("screen-first", true, "tvbox-warehouse-load"))
        navigation.abandoned("settings-advanced")
        assertEquals("tvbox-warehouse-url",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        navigation.clear()
        assertEquals("tvbox-warehouse-url",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
    }

    @Test fun movieSiteSelectionNavigatesIntoTmdbThenReturnsToSiteAction() {
        val navigation = CompactSettingsIntent()
        assertEquals("compact-tmdb-split-toggle",
            navigation.next("settings-source-sites", true, "settings-source-sites"))
        assertEquals("settings-source-sites",
            navigation.next("settings-source-sites", false, "settings-source-sites"))
    }

    @Test fun splitTokenSequenceSupportsFastRepeatAndReversal() {
        val navigation = CompactSettingsIntent()
        navigation.request("compact-tmdb-split-toggle")
        assertEquals("compact-tmdb-part-1",
            navigation.next("screen-first", true, "tvbox-warehouse-load", true))
        assertEquals("compact-tmdb-part-2",
            navigation.next("screen-first", true, "tvbox-warehouse-load", true))
        assertEquals("compact-tmdb-part-1",
            navigation.next("screen-first", false, "tvbox-warehouse-load", true))
        assertEquals("compact-tmdb-part-2",
            navigation.next("screen-first", true, "tvbox-warehouse-load", true))
    }
}
