package tv.herrin.app

enum class MediaEvidenceStage { SELECTED_TRACK }
enum class MediaOutputConfirmation { UNCONFIRMED }

/** Observations from Media3's *selected* playback tracks; not TMDB metadata or source-title claims. */
data class VerifiedStreamSpec(
    val resolution: String? = null,
    val dynamicRange: String? = null,
    val spatialAudio: String? = null,
    val dolbyAudio: String? = null
) {
    /** Selected metadata does not certify successful decoding or the television's output mode. */
    val evidenceStage: MediaEvidenceStage get() = MediaEvidenceStage.SELECTED_TRACK
    val outputConfirmation: MediaOutputConfirmation get() = MediaOutputConfirmation.UNCONFIRMED
    val isKnown: Boolean get() = resolution != null || dynamicRange != null || spatialAudio != null || dolbyAudio != null
    fun labels(): List<String> = listOfNotNull(resolution, dynamicRange, spatialAudio ?: dolbyAudio)
}

/** Values are extracted only after Media3 selected the corresponding track. */
internal data class SelectedTrackEvidence(
    val mime: String?, val width: Int = -1, val height: Int = -1,
    val colorTransfer: Int = -1
)

internal object MediaQuality {
    // Media3 C.COLOR_TRANSFER_ST2084=6 / HLG=7. Do not label HDR10+ without its
    // separately verifiable dynamic metadata, which Media3 Format may not expose.
    private const val ST2084 = 6
    private const val HLG = 7

    fun verified(video: SelectedTrackEvidence?, audio: SelectedTrackEvidence?): VerifiedStreamSpec {
        val width = video?.width?.coerceAtLeast(0) ?: 0
        val height = video?.height?.coerceAtLeast(0) ?: 0
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        val resolution = when {
            longSide >= 3840 && shortSide >= 1600 -> "4K"
            longSide >= 2560 && shortSide >= 1350 -> "1440p"
            longSide >= 1920 && shortSide >= 900 -> "1080p"
            longSide >= 1280 && shortSide >= 650 -> "720p"
            width > 0 && height > 0 -> "${shortSide}p"
            else -> null
        }
        val transfer = when {
            video?.mime == "video/dolby-vision" -> "Dolby Vision"
            video?.colorTransfer == ST2084 -> "HDR (PQ)"
            video?.colorTransfer == HLG -> "HDR (HLG)"
            else -> null
        }
        val spatial = if (audio?.mime == "audio/eac3-joc") "Dolby Atmos" else null
        val dolbyAudio = when (DeviceMediaCapabilities.dolbyAudioKind(audio?.mime)) {
            DolbyAudioKind.DOLBY_AUDIO -> "Dolby Audio"
            DolbyAudioKind.DOLBY_ATMOS -> "Dolby Atmos"
            null -> null
        }
        return VerifiedStreamSpec(resolution, transfer, spatial, dolbyAudio = dolbyAudio)
    }
}

/** Text supplied by an unverified provider is a *claim*, never a decoded format. */
internal object SourceMediaClaims {
    private val fourK = Regex("(?i)(?<![A-Za-z0-9])(?:4K|2160P)(?![A-Za-z0-9])")
    private val fullHd = Regex("(?i)(?<![A-Za-z0-9])(?:1080P|FHD)(?![A-Za-z0-9])")
    private val hd = Regex("(?i)(?<![A-Za-z0-9])720P(?![A-Za-z0-9])")
    private val hdr = Regex("(?i)(?<![A-Za-z0-9])HDR(?:10\\+?)?(?![A-Za-z0-9])")
    private val vision = Regex("(?i)(?<![A-Za-z0-9])(?:DV|DOLBY[ ._-]?VISION)(?![A-Za-z0-9])|杜比视界")
    private val atmos = Regex("(?i)(?<![A-Za-z0-9])(?:ATMOS|DOLBY[ ._-]?ATMOS)(?![A-Za-z0-9])|杜比全景声")

    fun labels(title: String, lineTitle: String): List<String> {
        // Never examine signed source URL: opaque parameters may contain misleading words.
        val advertised = "${title.take(160)} ${lineTitle.take(120)}"
        val labels = ArrayList<String>()
        when {
            fourK.containsMatchIn(advertised) -> labels += "4K"
            fullHd.containsMatchIn(advertised) -> labels += "1080p"
            hd.containsMatchIn(advertised) -> labels += "720p"
        }
        if (vision.containsMatchIn(advertised)) labels += "Dolby Vision"
        if (hdr.containsMatchIn(advertised)) labels += "HDR"
        if (atmos.containsMatchIn(advertised)) labels += "Dolby Atmos"
        return labels
    }
}
