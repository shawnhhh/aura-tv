package tv.herrin.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/** Read-only platform queries. Does not change the renderer, audio route, or HDR conversion. */
internal object DeviceMediaCapabilitiesAndroid {
    /** Invoke off the UI thread; codec enumeration can enter vendor services. */
    fun inspect(context: Context, displayId: Int = Display.DEFAULT_DISPLAY): DeviceMediaCapabilitySnapshot {
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        }.getOrNull()
        val hdr = runCatching { display?.hdrCapabilities?.supportedHdrTypes }
        // A failed vendor query is UNKNOWN; it must not prevent startup/playback.
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { codec ->
                codec.supportedTypes.filter { mime ->
                    mime.lowercase(java.util.Locale.ROOT) in DeviceMediaCapabilities.videoMimes +
                        DeviceMediaCapabilities.audioMimes
                }.map { mime ->
                    val profiles = runCatching {
                        codec.getCapabilitiesForType(mime).profileLevels.map { DecoderProfile(it.profile, it.level) }
                    }.getOrDefault(emptyList())
                    DeviceDecoderCapability(
                        codec.name, mime,
                        if (Build.VERSION.SDK_INT >= 29) {
                            if (codec.isHardwareAccelerated) MediaSupport.SUPPORTED else MediaSupport.UNSUPPORTED
                        } else MediaSupport.UNKNOWN,
                        profiles
                    )
                }
            }
        }
        val initial = DeviceMediaCapabilitySnapshot(
            displayId = display?.displayId,
            hdrFormats = DeviceMediaCapabilities.hdrFormats(hdr.getOrNull() ?: intArrayOf()),
            displayQuerySucceeded = hdr.isSuccess && hdr.getOrNull() != null,
            decoders = codecs.getOrDefault(emptyList()),
            decoderQuerySucceeded = codecs.isSuccess
        )
        val outputs = runCatching {
            requireNotNull(context.getSystemService(AudioManager::class.java))
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { output ->
                    DeviceAudioOutput(output.id, output.type,
                        output.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, output.encodings.toSet())
                }
        }
        return initial.copy(
            audioFormats = DeviceMediaCapabilities.audioMimes.map { mime ->
                DeviceAudioCapability(mime, initial.decoderSupport(mime), directPlayback(mime))
            },
            availableAudioOutputs = outputs.getOrDefault(emptyList()),
            audioOutputQuerySucceeded = outputs.isSuccess
        )
    }

    @Suppress("DEPRECATION")
    private fun directPlayback(mime: String): MediaSupport {
        if (Build.VERSION.SDK_INT < 29) return MediaSupport.UNKNOWN
        return runCatching {
            val encoding = when (mime) {
                "audio/ac3" -> AudioFormat.ENCODING_AC3
                "audio/eac3" -> AudioFormat.ENCODING_E_AC3
                "audio/eac3-joc" -> AudioFormat.ENCODING_E_AC3_JOC
                else -> return MediaSupport.UNKNOWN
            }
            val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            if (AudioTrack.isDirectPlaybackSupported(format, attributes)) MediaSupport.SUPPORTED
            else MediaSupport.UNSUPPORTED
        }.getOrDefault(MediaSupport.UNKNOWN)
    }
}
