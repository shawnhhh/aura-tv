package tv.herrin.app

import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executable on Android TV; does not assume the emulator has TCL/Dolby hardware. */
@RunWith(AndroidJUnit4::class)
class DeviceMediaCapabilitiesAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun displayCapabilitiesMatchFrameworkAndAbsentDisplayIsUnknown() {
        val observed = DeviceMediaCapabilitiesAndroid.inspect(context)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        assertTrue(observed.displayQuerySucceeded)
        assertEquals(DeviceMediaCapabilities.hdrFormats(display.hdrCapabilities.supportedHdrTypes), observed.hdrFormats)
        val missing = DeviceMediaCapabilitiesAndroid.inspect(context, Int.MAX_VALUE)
        assertFalse(missing.displayQuerySucceeded)
        assertEquals(MediaSupport.UNKNOWN, missing.hdrSupport(DeviceHdrFormat.DOLBY_VISION))
    }

    @Test fun decoderQueriesExcludeEncodersAndDoNotInventHardwareOrOutputSuccess() {
        val observed = DeviceMediaCapabilitiesAndroid.inspect(context)
        assertTrue(observed.decoderQuerySucceeded)
        val frameworkDecoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        observed.decoders.forEach { declared ->
            assertTrue(frameworkDecoders.any { codec -> codec.name == declared.name &&
                codec.supportedTypes.any { it.equals(declared.mime, true) } })
            if (Build.VERSION.SDK_INT >= 29) {
                val codec = frameworkDecoders.first { it.name == declared.name }
                assertEquals(if (codec.isHardwareAccelerated) MediaSupport.SUPPORTED else MediaSupport.UNSUPPORTED,
                    declared.hardwareAccelerated)
            } else assertEquals(MediaSupport.UNKNOWN, declared.hardwareAccelerated)
        }
        assertEquals(DeviceMediaCapabilities.audioMimes, observed.audioFormats.map { it.mime }.toSet())
        val selected = MediaQuality.verified(SelectedTrackEvidence("video/dolby-vision"),
            SelectedTrackEvidence("audio/eac3-joc"))
        assertEquals(MediaOutputConfirmation.UNCONFIRMED, selected.outputConfirmation)
    }
}
