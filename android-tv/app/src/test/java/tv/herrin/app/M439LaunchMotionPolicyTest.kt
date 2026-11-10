package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M439LaunchMotionPolicyTest {
    @Test fun completeRevealHasRequestedDurationAndBoundedFade() {
        assertTrue(LaunchMotion.WORDMARK_HOLD_MS in 1500L..2000L)
        assertTrue(LaunchMotion.FADE_MS in 200..500)
        assertEquals(WordmarkMotion.MIN_READY_MS.toLong()+LaunchMotion.FADE_MS,LaunchMotion.TARGET_ANIMATION_BUDGET_MS)
        assertTrue(LaunchMotion.BREATH_MS>=2400L)
        assertTrue(LaunchMotion.TARGET_ANIMATION_BUDGET_MS in 4300L..4900L)
    }
}
