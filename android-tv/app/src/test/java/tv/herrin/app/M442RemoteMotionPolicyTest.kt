package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M442RemoteMotionPolicyTest {
    @Test fun heroSettleIsShortAndDoesNotDelayDpadFocus() {
        assertTrue(RemoteMotionPolicy.HERO_DWELL_MS in 100L..250L)
        assertTrue(RemoteMotionPolicy.HERO_CROSSFADE_MS in 0..250)
        assertTrue(RemoteMotionPolicy.HERO_DWELL_MS +
            RemoteMotionPolicy.HERO_CROSSFADE_MS < 500)
        assertEquals(3, RemoteMotionPolicy.FOCUS_RETRY_FRAMES)
    }
}
