package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M439LaunchMotionPolicyTest {
    @Test fun completeRevealHasRequestedDurationAndBoundedFade() {
        assertTrue(LaunchMotion.WORDMARK_HOLD_MS in 1500L..2000L)
        assertTrue(LaunchMotion.FADE_MS in 200..500)
        assertTrue(LaunchMotion.TARGET_ANIMATION_BUDGET_MS in 1700L..2500L)
    }
}
