package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M439LaunchMotionPolicyTest {
    @Test fun completeRevealHasRequestedDurationAndBoundedFade() {
        assertEquals(2250L, LaunchMotion.WORDMARK_HOLD_MS)
        assertTrue(LaunchMotion.FADE_MS in 200..500)
        assertEquals(WordmarkMotion.MIN_READY_MS.toLong()+LaunchMotion.FADE_MS,LaunchMotion.TARGET_ANIMATION_BUDGET_MS)
        assertTrue(LaunchMotion.BREATH_MS>=2400L)
        assertTrue(LaunchMotion.TARGET_ANIMATION_BUDGET_MS in 3600L..3900L)
    }
}
