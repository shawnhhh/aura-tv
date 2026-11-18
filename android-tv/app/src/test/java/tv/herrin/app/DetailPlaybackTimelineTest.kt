package tv.herrin.app

import org.junit.Test
import org.junit.Assert.*

class DetailPlaybackTimelineTest {
    @Test fun monotonicEventsMeasureDistinctOriginsOnceAndResetForNextClick() {
        var now = 0L
        val events = mutableListOf<Pair<String, Long>>()
        val trace = DetailPlaybackTimeline({ now }) { code, ms -> events += code to ms }
        trace.enter(); now = 10; trace.hit(); now = 25; trace.details()
        now = 30; trace.linesDrawn(); trace.linesDrawn()
        now = 45; trace.episodesDrawn(); now = 50; trace.click()
        now = 70; trace.mediaReady(); now = 120; trace.firstFrame(); trace.firstFrame()
        assertEquals(listOf("ENTRY_TO_HIT" to 10L, "HIT_TO_DETAILS" to 15L,
            "HIT_TO_LINE_CPU_DRAW" to 20L, "HIT_TO_EPISODE_CPU_DRAW" to 35L,
            "CLICK_TO_MEDIA_READY" to 20L, "CLICK_TO_FIRST_FRAME" to 70L), events)
        trace.click(); now = 125; trace.firstFrame()
        assertEquals("CLICK_TO_FIRST_FRAME" to 5L, events.last())
    }
}
