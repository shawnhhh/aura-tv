package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshTest {
    @Test fun prefers120HzWhen4kOutputAnd120Hz4kModeExist() {
        val current = UiDisplayMode(3840, 2160, 60f)
        assertEquals(120f, preferredUiRefreshRate(current, listOf(
            current, UiDisplayMode(3840, 2160, 120f), UiDisplayMode(1920, 1080, 144f)
        ))!!, 0.01f)
    }

    @Test fun doesNotReduceResolutionOrForceUnsupportedRefresh() {
        val current = UiDisplayMode(3840, 2160, 60f)
        assertNull(preferredUiRefreshRate(current, listOf(
            current, UiDisplayMode(1920, 1080, 120f)
        )))
    }

    @Test fun gracefullySupports90HzAnd60HzOnlyTelevisions() {
        val current = UiDisplayMode(1920, 1080, 60f)
        assertEquals(90f, preferredUiRefreshRate(current, listOf(
            current, UiDisplayMode(1920, 1080, 90f)
        ))!!, 0.01f)
        assertNull(preferredUiRefreshRate(current, listOf(current)))
    }
}
