package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M438MetadataScrapePolicyTest {
    @Test fun startupIsBoundedAndOffscreenFilmsCanMatchOnFocus() {
        assertEquals(240, ArtworkScrapePolicy.STARTUP_LIMIT)
        assertTrue(ArtworkScrapePolicy.FOCUS_DWELL_MS >= 300)
        assertFalse(ArtworkScrapePolicy.shouldMatchFocused(-1, false))
        assertFalse(ArtworkScrapePolicy.shouldMatchFocused(0, false))
        assertFalse(ArtworkScrapePolicy.shouldMatchFocused(239, false))
        assertTrue(ArtworkScrapePolicy.shouldMatchFocused(240, false))
        assertTrue(ArtworkScrapePolicy.shouldMatchFocused(800, false))
        assertFalse(ArtworkScrapePolicy.shouldMatchFocused(800, true))
    }
}
