package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M449CompactSettingsIntentTest {
    @Test fun fourDownKeysReachFifthRowEvenBeforeAnyFocusCallback() {
        val navigation = CompactSettingsIntent()
        val commands = List(4) {
            navigation.next("screen-first", down = true, movieAction = "tvbox-warehouse-load")
        }
        assertEquals(listOf(
            "tvbox-warehouse-url", "tvbox-warehouse-load",
            "tvbox-live-direct-url", "tvbox-live-direct-load"
        ), commands)
        navigation.settled("tvbox-live-direct-load")
        assertEquals("settings-home",
            navigation.next("tvbox-live-direct-load", true, "tvbox-warehouse-load"))
    }

    @Test fun reversalsPreserveLatestRequestedDirectionRatherThanLastActualFocus() {
        val navigation = CompactSettingsIntent()
        assertEquals("tvbox-warehouse-url",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        assertEquals("tvbox-warehouse-load",
            navigation.next("screen-first", true, "tvbox-warehouse-load"))
        // Previous row focus callback can arrive late; it must not erase the
        // already queued newer destination.
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

    @Test fun movieSiteSelectionRemainsTheReturnTarget() {
        val navigation = CompactSettingsIntent()
        assertEquals("tvbox-live-direct-url",
            navigation.next("settings-source-sites", true, "settings-source-sites"))
        assertEquals("settings-source-sites",
            navigation.next("settings-source-sites", false, "settings-source-sites"))
    }
}
