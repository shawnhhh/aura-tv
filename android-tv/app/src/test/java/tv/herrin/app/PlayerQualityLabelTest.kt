package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQualityLabelTest {
    @Test fun fromActualFrameDimensions() {
        assertEquals("4K", PlayerQualityLabel.fromSize(3840, 2160))
        assertEquals("4K", PlayerQualityLabel.fromSize(2160, 3840))
        assertEquals("1440p", PlayerQualityLabel.fromSize(2560, 1440))
        assertEquals("1080p", PlayerQualityLabel.fromSize(1920, 1080))
        assertEquals("720p", PlayerQualityLabel.fromSize(1280, 720))
        assertEquals("480p", PlayerQualityLabel.fromSize(854, 480))
    }

    @Test fun noTrackMustNeverClaim4KOrHdrFromTheFilename() {
        assertEquals("清晰度识别中", PlayerQualityLabel.fromSize(0, 0))
        assertEquals("清晰度识别中", PlayerQualityLabel.fromSize(-1, 1080))
    }
}
