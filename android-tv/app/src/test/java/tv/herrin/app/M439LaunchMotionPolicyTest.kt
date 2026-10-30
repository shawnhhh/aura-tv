package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M439LaunchMotionPolicyTest {
    @Test fun logoIsOnlyHeldAndFadedNotCollapsedIntoH() {
        assertTrue(LaunchMotion.WORDMARK_HOLD_MS >= 0)
        assertTrue(LaunchMotion.FADE_MS in 200..500)
        assertTrue(LaunchMotion.TARGET_ANIMATION_BUDGET_MS < 600)
    }
}
