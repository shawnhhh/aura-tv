package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.herrin.kernel.SeekBounds

class KernelSeekBoundsTest {
    @Test fun clampsBeforeStartAfterEndAndUnknownTimelineWithoutOverflow() {
        assertEquals(0L, SeekBounds.offset(5000, -10000, 60000))
        assertEquals(60000L, SeekBounds.offset(55000, 10000, 60000))
        assertEquals(65000L, SeekBounds.offset(55000, 10000, -1))
        assertEquals(Long.MAX_VALUE, SeekBounds.offset(Long.MAX_VALUE - 1, 10000, -1))
        assertEquals(0L, SeekBounds.offset(0, Long.MIN_VALUE, -1))
    }
}
