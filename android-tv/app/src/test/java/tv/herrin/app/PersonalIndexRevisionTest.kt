package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalIndexRevisionTest {
    @Test fun indexRefreshDefersCacheInvalidationUntilPlaybackEndsAndAppliesEachRevisionOnce() {
        val guard = PersonalIndexRevisionGuard(0)
        assertFalse(guard.advance(0, false))
        assertFalse(guard.advance(1, true))
        assertFalse(guard.advance(2, true))
        assertTrue(guard.advance(2, false))
        assertFalse(guard.advance(2, false))
        assertTrue(guard.advance(3, false))
        assertFalse(guard.advance(1, false))
    }
}
