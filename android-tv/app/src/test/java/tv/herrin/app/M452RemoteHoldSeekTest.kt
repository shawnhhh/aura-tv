package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M452RemoteHoldSeekTest {
    @Test fun tappingDirectionStillSeeksExactlyTenSeconds() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 1_000L))
        assertNull(seek.up(1))
        assertEquals(-10_000L, seek.down(-1, 0, 2_000L))
        assertNull(seek.up(-1))
    }

    @Test fun holdingDirectionBatchesRepeatsInsteadOfFlushingDecoderEveryKey() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 1_000L))
        assertNull(seek.down(1, 1, 1_080L))
        assertNull(seek.down(1, 2, 1_160L))
        assertEquals(15_000L, seek.down(1, 3, 1_240L))
        assertNull(seek.down(1, 4, 1_300L))
        assertEquals(5_000L, seek.up(1))
        assertNull(seek.up(1))
    }

    @Test fun quickReleaseFlushesUncommittedRepeatIntent() {
        val seek = RemoteHoldSeek()
        assertEquals(-10_000L, seek.down(-1, 0, 10_000L))
        assertNull(seek.down(-1, 1, 10_060L))
        assertEquals(-5_000L, seek.up(-1))
        assertEquals(10_000L, seek.down(1, 0, 11_000L))
        assertNull(seek.up(-1))
    }

    @Test fun repeatedKeysAreBoundedWhenRenderThreadStalls() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 0L))
        repeat(50) { assertNull(seek.down(1, it + 1, 10L)) }
        assertEquals(RemoteHoldSeek.MAX_BATCH_MS, seek.down(1, 51, 300L))
        assertNull(seek.up(1))
    }

    @Test fun releaseInWrongDirectionDoesNotCancelActiveHold() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 1_000L))
        assertNull(seek.down(1, 1, 1_050L))
        assertNull(seek.up(-1))
        assertEquals(5_000L, seek.up(1))
    }

    @Test fun changingDirectionOrMediaItemResetsPendingIntent() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 1_000L))
        assertNull(seek.down(1, 1, 1_050L))
        assertEquals(-10_000L, seek.down(-1, 0, 1_070L))
        assertNull(seek.up(1))
        seek.reset() // A different media item must never inherit this hold.
        assertNull(seek.up(-1))
        assertEquals(-10_000L, seek.down(-1, 0, 2_000L))
    }

    @Test fun timeReversalDoesNotProduceNegativeTimingOrExtraSeek() {
        val seek = RemoteHoldSeek()
        assertEquals(10_000L, seek.down(1, 0, 1_000L))
        assertNull(seek.down(1, 1, 900L))
        assertEquals(5_000L, seek.up(1))
    }
}
