package tv.herrin.app

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test

class M455PlaybackViewportModeTest {
    @Test fun newInstallDefaultsToEdgeToEdgeCenterCrop() {
        assertEquals(PlaybackViewportMode.FILL,
            PlaybackViewportMode.fromStoredFill(true))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            PlaybackViewportMode.fromStoredFill(true).resizeMode)
    }

    @Test fun fitPreservesOriginalPictureInsteadOfStretching() {
        val fit = PlaybackViewportMode.FILL.toggle()
        assertEquals(PlaybackViewportMode.FIT, fit)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, fit.resizeMode)
        assertEquals(PlaybackViewportMode.FILL, fit.toggle())
    }

    @Test fun storedUserChoiceCanRestoreTheUncroppedMode() {
        assertEquals(PlaybackViewportMode.FIT,
            PlaybackViewportMode.fromStoredFill(false))
        assertEquals("完整显示", PlaybackViewportMode.FIT.label)
    }
}
