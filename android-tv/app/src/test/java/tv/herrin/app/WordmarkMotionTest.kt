package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
class WordmarkMotionTest {
    @Test fun v13RevealAndOverlappingBreath() {
        assertEquals(0f, WordmarkMotion.reveal(0f), 0f)
        assertEquals(0.5f, WordmarkMotion.reveal(1125f), 0.001f)
        val dim = WordmarkMotion.brightness(0f)
        assertTrue("Reveal should stay dim until the breath starts", dim in 0.25f..0.40f)
        assertEquals(dim, WordmarkMotion.brightness(1199f), 0.001f)
        assertEquals(dim, WordmarkMotion.brightness(1200f), 0.001f)
        assertEquals(0f, WordmarkMotion.glow(1199f), 0f)
        assertTrue(WordmarkMotion.brightness(1800f) > dim)
        assertTrue(WordmarkMotion.brightness(2050f) > WordmarkMotion.brightness(1800f))
        assertEquals(1f, WordmarkMotion.reveal(2250f), 0f)
        assertEquals(1f, WordmarkMotion.brightness(2250f), 0.001f)
        assertEquals(1f, WordmarkMotion.glow(2250f), 0.001f)
        assertEquals(0.30f, WordmarkMotion.brightness(3450f), 0.001f)
        assertEquals(0f, WordmarkMotion.glow(3450f), 0.001f)
        assertFalse(WordmarkMotion.ready(2249f))
        assertTrue(WordmarkMotion.ready(2250f))
        assertEquals(WordmarkMotion.brightness(2250f), WordmarkMotion.brightness(4650f), 0.001f)
        assertEquals(WordmarkMotion.brightness(3000f), WordmarkMotion.brightness(5400f), 0.001f)
    }
}
