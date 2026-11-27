package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M452RemoteHoldSeekTest {
    @Test fun tappingCommitsExactlyTenSecondsOnlyOnRelease() {
        val seek = RemoteHoldSeek()
        assertFalse(seek.down(1,0,1000,20000,120000))
        assertNull(seek.preview(1050))
        assertEquals(30000L,seek.up(1,1100,20000,120000))
        assertNull(seek.up(1,1200,30000,120000))
    }
    @Test fun holdPreviewsSmoothlyByElapsedTimeWithoutRepeatRateDependentJumps() {
        val seek = RemoteHoldSeek()
        seek.down(1,0,1000,20000,120000)
        assertTrue(seek.down(1,1,1500,20500,120000,1000))
        assertEquals(20500L,seek.preview(1500))
        assertEquals(20660L,seek.preview(1516))
        assertEquals(25500L,seek.preview(2000))
        repeat(100) { assertFalse(seek.down(1,it+2,2000,20500,120000,1000)) }
        assertEquals(25500L,seek.preview(2000))
        assertEquals(35500L,seek.up(1,3000,20500,120000))
        assertNull(seek.preview(3100))
    }
    @Test fun reversePreviewAndTimelineEndsAreClamped() {
        val seek = RemoteHoldSeek()
        seek.down(-1,0,1000,5000,120000)
        seek.down(-1,1,1500,5000,120000,1000)
        assertEquals(0L,seek.preview(2500))
        assertEquals(0L,seek.up(-1,2500,5000,120000))
        seek.down(1,0,3000,115000,120000)
        seek.down(1,1,3500,115000,120000,3000)
        assertEquals(120000L,seek.up(1,4500,115000,120000))
    }
    @Test fun cancellationAndMediaChangeDiscardPreviewWithoutAnyCommit() {
        val seek = RemoteHoldSeek()
        seek.down(1,0,1000,20000,120000);seek.down(1,1,1500,20000,120000,1000)
        seek.reset()
        assertFalse(seek.isHeld);assertFalse(seek.isLongPress)
        assertNull(seek.up(1,2000,20000,120000));assertNull(seek.preview(3000))
    }
    @Test fun wrongReleaseAndOrphanRepeatsCannotCreateASeek() {
        val seek = RemoteHoldSeek()
        assertFalse(seek.down(1,8,1500,20000,120000,1000))
        assertFalse(seek.isHeld)
        seek.down(-1,0,2000,20000,120000)
        assertNull(seek.up(1,2100,20000,120000))
        assertEquals(10000L,seek.up(-1,2200,20000,120000))
    }
    @Test fun directionChangeCannotInheritThePreviousPreview() {
        val seek = RemoteHoldSeek()
        seek.down(1,0,1000,20000,120000);seek.down(1,1,1500,20000,120000,1000)
        assertEquals(30000L,seek.preview(2500))
        seek.down(-1,0,2600,20000,120000)
        assertFalse(seek.isLongPress)
        assertEquals(10000L,seek.up(-1,2700,20000,120000))
    }
    @Test fun reversedClockCannotAdvanceThePreview() {
        val seek = RemoteHoldSeek()
        seek.down(1,0,1000,20000,-1);seek.down(1,1,1500,20000,-1,1000)
        assertEquals(20000L,seek.preview(1400))
    }
    @Test fun remotesRepeatingWithZeroCountStillShareThePhysicalGesture() {
        val seek = RemoteHoldSeek()
        seek.down(1,0,1000,20000,-1,1000)
        assertTrue(seek.down(1,0,1500,20000,-1,1000))
        assertFalse(seek.down(1,0,1600,20000,-1,1000))
        assertEquals(21000L,seek.up(1,1600,20000,-1))
    }
    @Test fun sustainedPreviewAcceleratesSymmetricallyAndReleasesAtThePreview() {
        val forward = RemoteHoldSeek()
        val reverse = RemoteHoldSeek()
        for ((direction, hold) in listOf(1 to forward, -1 to reverse)) {
            hold.down(direction, 0, 1000, 900_000, 2_000_000)
            hold.down(direction, 1, 1500, 900_000, 2_000_000, 1000)
        }
        val early = forward.preview(2500)!! - forward.preview(1500)!!
        val late = forward.preview(6500)!! - forward.preview(5500)!!
        assertTrue(late > early)
        assertTrue(late <= 120_000)
        val target = forward.preview(6500)!!
        assertEquals(900_000L - (target - 900_000L), reverse.preview(6500))
        assertEquals(target, forward.up(1, 6500, 900_000, 2_000_000, 1000))
        assertNull(forward.up(1, 6501, 900_000, 2_000_000, 1000))
    }
}
