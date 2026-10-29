package tv.herrin.app

/** A platform declaration is not proof that a particular stream was rendered/output. */
internal enum class MediaSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }
internal enum class DeviceHdrFormat { DOLBY_VISION, HDR10, HDR10_PLUS, HLG }
internal enum class DolbyAudioKind { DOLBY_AUDIO, DOLBY_ATMOS }

internal data class DecoderProfile(val profile: Int, val level: Int)
internal data class DeviceDecoderCapability(
    val name: String,
    val mime: String,
    val hardwareAccelerated: MediaSupport,
    val profiles: List<DecoderProfile> = emptyList()
)

internal data class DeviceAudioCapability(
    val mime: String,
    val decoderSupport: MediaSupport,
    /** AudioTrack's declaration for exactly [sampleRateHz]/[channelCount], not output success. */
    val directPlaybackSupport: MediaSupport,
    val sampleRateHz: Int = 48_000,
    val channelCount: Int = 6
)

internal data class DeviceAudioOutput(
    val id: Int,
    val type: Int,
    val builtInSpeaker: Boolean,
    /** Available device encodings. Empty means no declaration, not a confirmed rejection. */
    val encodings: Set<Int>
)

/** Snapshot must be refreshed after display/audio route changes. No model-name assumptions. */
internal data class DeviceMediaCapabilitySnapshot(
    val displayId: Int? = null,
    val hdrFormats: Set<DeviceHdrFormat> = emptySet(),
    val displayQuerySucceeded: Boolean = false,
    val decoders: List<DeviceDecoderCapability> = emptyList(),
    val decoderQuerySucceeded: Boolean = false,
    val audioFormats: List<DeviceAudioCapability> = emptyList(),
    /** These are available outputs; getDevices does not identify the current playback route. */
    val availableAudioOutputs: List<DeviceAudioOutput> = emptyList(),
    val audioOutputQuerySucceeded: Boolean = false
) {
    fun hdrSupport(format: DeviceHdrFormat): MediaSupport = when {
        !displayQuerySucceeded -> MediaSupport.UNKNOWN
        format in hdrFormats -> MediaSupport.SUPPORTED
        else -> MediaSupport.UNSUPPORTED
    }

    fun decoderSupport(mime: String, requireHardware: Boolean = false): MediaSupport {
        if (!decoderQuerySucceeded) return MediaSupport.UNKNOWN
        val matching = decoders.filter { it.mime.equals(mime, ignoreCase = true) }
        if (matching.isEmpty()) return MediaSupport.UNSUPPORTED
        if (!requireHardware) return MediaSupport.SUPPORTED
        return when {
            matching.any { it.hardwareAccelerated == MediaSupport.SUPPORTED } -> MediaSupport.SUPPORTED
            matching.any { it.hardwareAccelerated == MediaSupport.UNKNOWN } -> MediaSupport.UNKNOWN
            else -> MediaSupport.UNSUPPORTED
        }
    }
}

/** Stable numeric Android HDR values, independently testable without an Android runtime. */
internal object DeviceMediaCapabilities {
    val videoMimes = setOf("video/hevc", "video/dolby-vision")
    val audioMimes = setOf("audio/ac3", "audio/eac3", "audio/eac3-joc")

    fun hdrFormats(types: IntArray): Set<DeviceHdrFormat> = types.asSequence().mapNotNull { type ->
        when (type) {
            1 -> DeviceHdrFormat.DOLBY_VISION
            2 -> DeviceHdrFormat.HDR10
            3 -> DeviceHdrFormat.HLG
            4 -> DeviceHdrFormat.HDR10_PLUS
            else -> null
        }
    }.toSet()

    fun dolbyAudioKind(mime: String?): DolbyAudioKind? = when (mime?.lowercase(java.util.Locale.ROOT)) {
        "audio/ac3", "audio/eac3" -> DolbyAudioKind.DOLBY_AUDIO
        "audio/eac3-joc" -> DolbyAudioKind.DOLBY_ATMOS
        else -> null
    }
}
