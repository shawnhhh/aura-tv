package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
class WordmarkMotionTest {
    @Test fun revealPrecedesSynchronousBreathing() {
        assertEquals(0f, WordmarkMotion.reveal(0f), 0f)
        assertEquals(0.5f, WordmarkMotion.reveal(900f), 0.001f)
        assertEquals(1f, WordmarkMotion.brightness(1799f), 0f)
        assertEquals(1f, WordmarkMotion.reveal(1800f), 0f)
        assertEquals(1f, WordmarkMotion.brightness(1800f), 0.001f)
        assertEquals(0.7f, WordmarkMotion.brightness(3000f), 0.001f)
    }
}
