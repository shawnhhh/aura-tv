package tv.herrin.app

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test

class M455PlaybackViewportModeTest {
    @Test fun oldDefaultCropSettingCannotCropAnUpgrade() {
        for (legacy in listOf(true, false)) {
            assertEquals(PlaybackViewportMode.FIT, PlaybackViewportMode.fromStoredFill(legacy))
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, PlaybackViewportMode.fromStoredFill(legacy).resizeMode)
        }
        assertEquals(listOf(PlaybackViewportMode.FIT), PlaybackViewportMode.entries)
    }
}
