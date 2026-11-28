package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalIndexRevisionTest {
    @Test fun indexRefreshDefersCacheInvalidationUntilPlaybackEndsAndAppliesEachRevisionOnce() {
        val guard = PersonalIndexRevisionGuard(0)
        assertFalse(guard.advance(0, "browse"))
        assertFalse(guard.advance(1, "player"))
        assertFalse(guard.advance(2, "player"))
        assertTrue(guard.advance(2, "browse"))
        assertFalse(guard.advance(2, "browse"))
        assertTrue(guard.advance(3, "browse"))
        assertFalse(guard.advance(1, "browse"))
    }
    @Test fun scansCannotRestartDetailRebindingOrARecentCardBeforeThePlayerExists() {
        val guard = PersonalIndexRevisionGuard(10)
        assertFalse(guard.advance(11, "detail"))
        assertFalse(guard.advance(12, "play-intent"))
        assertFalse(guard.advance(13, "player"))
        // All scans coalesce into one refresh after the transaction leaves.
        assertTrue(guard.advance(13, "browse"))
        assertFalse(guard.advance(13, "browse"))
        assertFalse(guard.advance(12, "settings"))
        assertTrue(guard.advance(14, "settings"))
    }
}
