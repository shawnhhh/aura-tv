package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class DeviceMediaCapabilitiesTest {
    @Test fun `HDR declarations preserve all four formats without conflating PQ and plus`() {
        assertEquals(setOf(DeviceHdrFormat.DOLBY_VISION, DeviceHdrFormat.HDR10,
            DeviceHdrFormat.HDR10_PLUS, DeviceHdrFormat.HLG),
            DeviceMediaCapabilities.hdrFormats(intArrayOf(1, 2, 3, 4, 99, 1)))
        assertEquals(setOf(DeviceHdrFormat.HDR10), DeviceMediaCapabilities.hdrFormats(intArrayOf(2)))
        assertTrue(DeviceMediaCapabilities.hdrFormats(intArrayOf(0, 99)).isEmpty())
    }

    @Test fun `query failures are unknown but successfully queried SDR display is unsupported`() {
        assertEquals(MediaSupport.UNKNOWN, DeviceMediaCapabilitySnapshot().hdrSupport(DeviceHdrFormat.HDR10))
        assertEquals(MediaSupport.UNSUPPORTED,
            DeviceMediaCapabilitySnapshot(displayQuerySucceeded = true).hdrSupport(DeviceHdrFormat.HDR10))
        val snapshot = DeviceMediaCapabilitySnapshot(displayQuerySucceeded = true,
            hdrFormats = setOf(DeviceHdrFormat.HDR10))
        assertEquals(MediaSupport.SUPPORTED, snapshot.hdrSupport(DeviceHdrFormat.HDR10))
        assertEquals(MediaSupport.UNSUPPORTED, snapshot.hdrSupport(DeviceHdrFormat.HDR10_PLUS))
    }

    @Test fun `software decoder does not establish hardware support`() {
        val snapshot = DeviceMediaCapabilitySnapshot(decoderQuerySucceeded = true, decoders = listOf(
            DeviceDecoderCapability("software", "video/hevc", MediaSupport.UNSUPPORTED)))
        assertEquals(MediaSupport.SUPPORTED, snapshot.decoderSupport("VIDEO/HEVC"))
        assertEquals(MediaSupport.UNSUPPORTED, snapshot.decoderSupport("video/hevc", requireHardware = true))
        assertEquals(MediaSupport.UNSUPPORTED, snapshot.decoderSupport("video/dolby-vision", requireHardware = true))
    }

    @Test fun `legacy hardware classification remains unknown until platform confirms it`() {
        val snapshot = DeviceMediaCapabilitySnapshot(decoderQuerySucceeded = true, decoders = listOf(
            DeviceDecoderCapability("vendor", "video/hevc", MediaSupport.UNKNOWN)))
        assertEquals(MediaSupport.UNKNOWN, snapshot.decoderSupport("video/hevc", requireHardware = true))
        assertEquals(MediaSupport.UNKNOWN, DeviceMediaCapabilitySnapshot().decoderSupport("video/hevc"))
        assertEquals(MediaSupport.SUPPORTED, snapshot.copy(decoders = snapshot.decoders +
            DeviceDecoderCapability("hardware", "video/hevc", MediaSupport.SUPPORTED))
            .decoderSupport("video/hevc", requireHardware = true))
    }

    @Test fun `HDR and Dolby Vision decoder support are independent`() {
        val snapshot = DeviceMediaCapabilitySnapshot(displayQuerySucceeded = true,
            hdrFormats = setOf(DeviceHdrFormat.DOLBY_VISION), decoderQuerySucceeded = true)
        assertEquals(MediaSupport.SUPPORTED, snapshot.hdrSupport(DeviceHdrFormat.DOLBY_VISION))
        assertEquals(MediaSupport.UNSUPPORTED, snapshot.decoderSupport("video/dolby-vision"))
    }

    @Test fun `AC3 and EAC3 are Dolby Audio while JOC is an Atmos track`() {
        assertEquals(DolbyAudioKind.DOLBY_AUDIO, DeviceMediaCapabilities.dolbyAudioKind("audio/ac3"))
        assertEquals(DolbyAudioKind.DOLBY_AUDIO, DeviceMediaCapabilities.dolbyAudioKind("audio/eac3"))
        assertEquals(DolbyAudioKind.DOLBY_ATMOS, DeviceMediaCapabilities.dolbyAudioKind("AUDIO/EAC3-JOC"))
        assertNull(DeviceMediaCapabilities.dolbyAudioKind("audio/mp4a-latm"))
        assertNull(DeviceMediaCapabilities.dolbyAudioKind(null))
    }

    @Test fun `selected Atmos is never a successful output confirmation`() {
        val selected = MediaQuality.verified(SelectedTrackEvidence("video/dolby-vision", 3840, 2160),
            SelectedTrackEvidence("audio/eac3-joc"))
        assertEquals("Dolby Atmos", selected.spatialAudio)
        assertEquals("Dolby Atmos", selected.dolbyAudio)
        assertEquals(MediaEvidenceStage.SELECTED_TRACK, selected.evidenceStage)
        assertEquals(MediaOutputConfirmation.UNCONFIRMED, selected.outputConfirmation)
        assertEquals("Dolby Audio", MediaQuality.verified(null, SelectedTrackEvidence("audio/ac3")).dolbyAudio)
        assertEquals("Dolby Audio", MediaQuality.verified(null, SelectedTrackEvidence("audio/eac3")).dolbyAudio)
    }

    @Test fun `PQ track never inherits display HDR10 plus declaration`() {
        val declared = DeviceMediaCapabilitySnapshot(displayQuerySucceeded = true,
            hdrFormats = setOf(DeviceHdrFormat.HDR10_PLUS))
        val selected = MediaQuality.verified(SelectedTrackEvidence("video/hevc", 3840, 2160, 6), null)
        assertEquals(MediaSupport.SUPPORTED, declared.hdrSupport(DeviceHdrFormat.HDR10_PLUS))
        assertEquals("HDR (PQ)", selected.dynamicRange)
        assertFalse(selected.labels().any { it.contains("HDR10+") })
        assertEquals(MediaOutputConfirmation.UNCONFIRMED, selected.outputConfirmation)
    }

    @Test fun `direct playback and speaker availability never confirm Atmos output`() {
        val snapshot = DeviceMediaCapabilitySnapshot(audioFormats = listOf(
            DeviceAudioCapability("audio/eac3-joc", MediaSupport.SUPPORTED, MediaSupport.SUPPORTED)),
            availableAudioOutputs = listOf(DeviceAudioOutput(1, 2, true, emptySet())),
            audioOutputQuerySucceeded = true)
        assertTrue(snapshot.availableAudioOutputs.single().builtInSpeaker)
        assertEquals(6, snapshot.audioFormats.single().channelCount)
        val selected = MediaQuality.verified(null, SelectedTrackEvidence("audio/eac3-joc"))
        assertEquals(MediaOutputConfirmation.UNCONFIRMED, selected.outputConfirmation)
    }
}
