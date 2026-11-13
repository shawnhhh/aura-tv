package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class KernelMediaAdmissionTest {
    @Test fun dolbyProfilesRequireExactHardwareDeclarationAndNeverProveDualLayer() {
        val snapshot = DeviceMediaCapabilitySnapshot(displayQuerySucceeded = true,
            hdrFormats = setOf(DeviceHdrFormat.DOLBY_VISION), decoderQuerySucceeded = true,
            decoders = listOf(DeviceDecoderCapability("vendor", "video/dolby-vision",
                MediaSupport.SUPPORTED, listOf(DecoderProfile(32, 1), DecoderProfile(128, 1)))))
        assertEquals(MediaSupport.SUPPORTED, snapshot.dolbyVisionAdmission(DolbyVisionProfile.PROFILE_5).hardwareDecoder)
        assertEquals(MediaSupport.UNSUPPORTED, snapshot.dolbyVisionAdmission(DolbyVisionProfile.PROFILE_8).hardwareDecoder)
        assertFalse(snapshot.dolbyVisionAdmission(DolbyVisionProfile.PROFILE_7).fullDualLayerVerified)
        assertEquals(MediaSupport.UNKNOWN, DeviceMediaCapabilitySnapshot().dolbyVisionAdmission(DolbyVisionProfile.PROFILE_5).hardwareDecoder)
    }
    @Test fun audioDirectDeclarationCannotBeReusedForAnotherStreamShape() {
        val snapshot = DeviceMediaCapabilitySnapshot(decoderQuerySucceeded = true,
            decoders = listOf(DeviceDecoderCapability("decoder", "audio/true-hd", MediaSupport.UNSUPPORTED)),
            audioFormats = listOf(DeviceAudioCapability("audio/true-hd", MediaSupport.SUPPORTED, MediaSupport.SUPPORTED)))
        assertEquals(AudioDelivery.DIRECT_CANDIDATE, snapshot.audioDelivery("audio/true-hd", 48000, 6))
        assertEquals(AudioDelivery.DECODE_TO_PCM, snapshot.audioDelivery("audio/true-hd", 192000, 8))
        assertEquals(AudioDelivery.UNVERIFIED, snapshot.audioDelivery("audio/vnd.dts.hd", 48000, 8))
    }
}
