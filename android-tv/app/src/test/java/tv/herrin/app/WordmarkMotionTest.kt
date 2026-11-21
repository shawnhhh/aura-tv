package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test

class WordmarkMotionTest {
    @Test fun v13CompleteFeatherRevealPrecedesBrighteningAndBreathing() {
        assertEquals(0f, WordmarkMotion.reveal(0f), 0f)
        assertEquals(0.5f, WordmarkMotion.reveal(1125f), 0.001f)
        assertEquals(1f, WordmarkMotion.reveal(2250f), 0f)
        val revealLevel = WordmarkMotion.REVEAL_BRIGHTNESS
        assertTrue("The reveal should be visible, not nearly black", revealLevel in 0.53f..0.65f)
        for (time in listOf(0f, 1199f, 1200f, 1800f, 2249f, 2250f)) {
            assertEquals("No brightening before every letter is visible", revealLevel, WordmarkMotion.brightness(time), 0.001f)
            assertEquals("No letter glow during reveal", 0f, WordmarkMotion.glow(time), 0.001f)
        }
        assertTrue(WordmarkMotion.brightness(2450f) > revealLevel)
        assertTrue(WordmarkMotion.brightness(2850f) > WordmarkMotion.brightness(2450f))
        assertEquals(1f, WordmarkMotion.brightness(3100f), 0.001f)
        assertEquals(1f, WordmarkMotion.glow(3100f), 0.001f)
        assertTrue("Breath must not get too dark", WordmarkMotion.brightness(4300f) >= 0.55f)
        assertEquals(0.58f, WordmarkMotion.brightness(4300f), 0.001f)
        assertEquals(0f, WordmarkMotion.glow(4300f), 0.001f)
        assertEquals(WordmarkMotion.brightness(4300f), WordmarkMotion.brightness(6700f), 0.001f)
        assertEquals(WordmarkMotion.brightness(3600f), WordmarkMotion.brightness(6000f), 0.001f)
        assertFalse(WordmarkMotion.ready(3399f))
        assertTrue(WordmarkMotion.ready(3400f))
    }
}
