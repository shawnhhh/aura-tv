package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NavMotionGeometryTest {
    @Test fun indicatorCentersOnTheMeasuredButtonInsteadOfUsingAssumedTabWidths() {
        val first = navTrackBounds(NavPillBounds(0f, 100f))
        val second = navTrackBounds(NavPillBounds(117f, 140f))

        assertEquals(19f, first.leftPx, 0.01f)
        assertEquals(62f, first.widthPx, 0.01f)
        assertEquals(143.6f, second.leftPx, 0.01f)
        assertEquals(86.8f, second.widthPx, 0.01f)
    }

    @Test fun undersizedBoundsRemainFiniteAndDrawable() {
        val result = navTrackBounds(NavPillBounds(14f, 0f))
        assertEquals(14f, result.leftPx, 0.01f)
        assertEquals(1f, result.widthPx, 0.01f)
    }
}
