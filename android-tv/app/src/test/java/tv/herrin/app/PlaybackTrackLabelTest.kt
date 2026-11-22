package tv.herrin.app

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class PlaybackTrackLabelTest {
    @Test fun channelCountAndMarketingLabelsDoNotInventAtmos() {
        val label = PlaybackTrackLabel.fromFacts("en", "Dolby Atmos 全景声", "audio/eac3", 8, C.TRACK_TYPE_AUDIO, 0)
        assertFalse(label.contains("Atmos")); assertFalse(label.contains("全景声"))
        assertTrue(label.contains("8 声道")); assertTrue(label.contains("E-AC-3"))
    }
    @Test fun onlyTheActualJocMimeReportsAtmos() {
        assertTrue(PlaybackTrackLabel.fromFacts(null, null, "audio/eac3-joc", 6, C.TRACK_TYPE_AUDIO, 0).contains("Dolby Atmos"))
        assertFalse(PlaybackTrackLabel.fromFacts(null, null, "audio/true-hd", 8, C.TRACK_TYPE_AUDIO, 0).contains("Atmos"))
    }
    @Test fun dtsComesFromTheFormatAndUnknownChannelsRemainUnknown() {
        val aac = PlaybackTrackLabel.fromFacts(null, "DTS-HD", "audio/mp4a-latm", -1, C.TRACK_TYPE_AUDIO, 0)
        assertFalse(aac.contains("DTS")); assertFalse(aac.contains("声道")); assertTrue(aac.contains("AAC"))
        assertTrue(PlaybackTrackLabel.fromFacts(null, null, "audio/vnd.dts.hd", 6, C.TRACK_TYPE_AUDIO, 0).contains("DTS-HD"))
    }
    @Test fun subtitleLabelsRetainExternalNamesWithoutAudioMetadata() {
        val label = PlaybackTrackLabel.fromFacts("zh", "外挂中文", "text/vtt", 6, C.TRACK_TYPE_TEXT, 0)
        assertTrue(label.contains("外挂中文")); assertFalse(label.contains("声道"))
    }
}
