package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun ratesCycleFromNormalToFasterAndWrap() {
        assertEquals(1.25f, PlaybackTransport.nextSpeed(1f))
        assertEquals(1.5f, PlaybackTransport.nextSpeed(1.25f))
        assertEquals(0.5f, PlaybackTransport.nextSpeed(2f))
        assertEquals(0.5f, PlaybackTransport.nextSpeed(3f))
        assertEquals(6, PlaybackTransport.speeds.size)
    }
}
