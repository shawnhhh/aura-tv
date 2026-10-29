package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun reportsDecoderDimensionsAndInputFrameRateOnlyAfterFramesExist() {
        assertEquals("清晰度识别中", PlayerQualityLabel.fromDecoded(3840, 2160, 60f, "video/hevc", 6, 0))
        assertEquals("1080p · 1920×1080 · 23.976 fps · SDR",
            PlayerQualityLabel.fromDecoded(1920, 1080, 23.976f, "video/avc", 3, 12))
        assertEquals("180p · 320×180 · 帧率未提供 · HDR 信息未提供",
            PlayerQualityLabel.fromDecoded(320, 180, Float.NaN, "video/avc", -1, 12))
    }

    @Test fun hdrAndDolbyInputNeverCertifyPanelOutputOrInventDynamicMetadata() {
        val pq = PlayerQualityLabel.fromDecoded(3840, 2160, 24f, "video/hevc", 6, 12)
        assertTrue(pq.contains("HDR PQ 片源（输出待确认）"))
        assertFalse(pq.contains("HDR10+"))
        val dv = PlayerQualityLabel.fromDecoded(3840, 2160, 24f, "video/dolby-vision", 6, 12)
        assertTrue(dv.contains("Dolby Vision 片源（输出待确认）"))
    }

    @Test fun noTrackMustNeverClaim4KOrHdrFromTheFilename() {
        assertEquals("清晰度识别中", PlayerQualityLabel.fromSize(0, 0))
        assertEquals("清晰度识别中", PlayerQualityLabel.fromSize(-1, 1080))
    }
}
