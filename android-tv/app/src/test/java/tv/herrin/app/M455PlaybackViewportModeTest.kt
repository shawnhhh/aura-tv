package tv.herrin.app

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test

class M455PlaybackViewportModeTest {
    @Test fun oldDefaultCropSettingCannotCropAnUpgrade() {
        assertEquals(PlaybackViewportMode.FIT, PlaybackViewportMode.fromStoredFill(true))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, PlaybackViewportMode.fromStoredFill(true).resizeMode)
    }
    @Test fun newInstallAndPreviousFitChoicePreserveOriginalPicture() {
        assertEquals(PlaybackViewportMode.FIT, PlaybackViewportMode.fromStoredFill(false))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, PlaybackViewportMode.fromStoredFill(false).resizeMode)
    }
    @Test fun noSelectableModeCanCropOrStretch() {
        assertEquals(listOf(PlaybackViewportMode.FIT), PlaybackViewportMode.entries)
        assertEquals("完整显示（等比例）", PlaybackViewportMode.FIT.label)
    }
}
