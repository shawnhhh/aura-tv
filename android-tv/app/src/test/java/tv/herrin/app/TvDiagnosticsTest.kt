package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TvDiagnosticsTest {
    @Test fun outputDoesNotClaimHintIsRenderedFpsOrLeakAnyPrivateUrl() {
        val value = deviceStatusSummary(UiDisplayMode(3840, 2160, 60f), 120f, 5, true)
        assertTrue(value.contains("3840×2160 @ 60.00Hz"))
        assertTrue(value.contains("120.00Hz"))
        assertTrue(value.contains("提示值不等于实际帧率"))
        assertTrue(value.contains("已保存目录：5 个"))
        assertFalse(value.contains("https://"))
        assertFalse(value.contains("token"))
    }
}
