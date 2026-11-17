package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
class WordmarkMotionTest {
    @Test fun v13RevealAndOverlappingBreath() {
        assertEquals(0f, WordmarkMotion.reveal(0f), 0f)
        assertEquals(0.5f, WordmarkMotion.reveal(1125f), 0.001f)
        assertEquals(1f, WordmarkMotion.brightness(1199f), 0f)
        assertEquals(1f, WordmarkMotion.reveal(2250f), 0f)
        assertEquals(1f, WordmarkMotion.brightness(1200f), 0.001f)
        assertEquals(0.7f, WordmarkMotion.brightness(2400f), 0.001f)
        assertFalse(WordmarkMotion.ready(2249f))
        assertTrue(WordmarkMotion.ready(2250f))
        assertEquals(WordmarkMotion.brightness(2400f), WordmarkMotion.brightness(4800f), 0.001f)
    }
}
