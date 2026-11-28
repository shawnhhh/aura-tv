package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class WordmarkFrameClockTest {
    @Test fun composeHandoffResumesTheNativeFrameWithoutSkippingTheReveal() {
        val clock = WordmarkFrameClock(32f)
        assertEquals(32f, clock.frame(1_000_000_000, false), 0f)
        assertEquals(32f, clock.frame(2_000_000_000, true), 0f)
        assertEquals(48f, clock.frame(2_016_000_000, true), 0f)
        assertFalse(WordmarkMotion.ready(clock.elapsedMs))
    }
    @Test fun firstInstallDelayCannotSkipReveal() {
        val clock = WordmarkFrameClock()
        clock.frame(0, false)
        assertEquals(0f, clock.frame(8_000_000_000, false), 0f)
        assertEquals(0f, clock.frame(9_000_000_000, true), 0f)
        assertEquals(16f, clock.frame(9_016_000_000, true), 0f)
        assertEquals(80f, clock.frame(15_000_000_000, true), 0f)
        assertFalse(WordmarkMotion.ready(clock.elapsedMs))
    }
    @Test fun normalVsyncPreservesDurationAndWindowPauseDoesNotAdvance() {
        val clock = WordmarkFrameClock()
        for (i in 0..225) clock.frame(i * 16_000_000L, true)
        assertEquals(3600f, clock.elapsedMs, 0f)
        clock.frame(10_000_000_000, false)
        clock.frame(12_000_000_000, true)
        assertEquals(3600f, clock.elapsedMs, 0f)
        assertTrue(WordmarkMotion.ready(clock.elapsedMs))
    }
}
