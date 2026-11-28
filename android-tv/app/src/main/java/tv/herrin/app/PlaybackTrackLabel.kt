package tv.herrin.app

import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.Locale

/** Track Format only: channel count or a marketing label cannot prove Atmos. */
internal object PlaybackTrackLabel {
    fun from(format: Format, type: Int, ordinal: Int): String =
        fromFacts(format.language, format.label, format.sampleMimeType, format.channelCount, type, ordinal)

    internal fun fromFacts(languageCode: String?, declaredLabel: String?, mime: String?, channels: Int,
        type: Int, ordinal: Int): String {
        val language = languageCode?.takeUnless { it.isBlank() || it == "und" }?.let {
            Locale.forLanguageTag(it).getDisplayName(Locale.SIMPLIFIED_CHINESE).takeIf(String::isNotBlank)
        }
        val safeLabel = if (type == C.TRACK_TYPE_AUDIO) declaredLabel?.replace(
            Regex("(?i)(?:杜比全景声|全景声|\\b(?:Dolby[ -]?Atmos|Atmos|DTS(?:[ :.-]?(?:HD|MA|X))?)\\b)"), "") else declaredLabel
        val label = safeLabel?.trim()?.takeIf(String::isNotBlank)
        val parts = mutableListOf(label ?: language ?: (if (type == C.TRACK_TYPE_TEXT) "字幕 " else "音轨 ") + (ordinal + 1))
        if (label != null && language != null && !label.contains(language, true)) parts += language
        if (type == C.TRACK_TYPE_AUDIO) {
            if (channels > 0) parts += "${channels} 声道"
            val codec = when (mime) {
                "audio/eac3-joc" -> "Dolby Atmos"
                "audio/eac3" -> "E-AC-3"
                "audio/ac3" -> "AC-3"
                "audio/vnd.dts" -> "DTS"
                "audio/vnd.dts.hd" -> "DTS-HD"
                "audio/vnd.dts.uhd", "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
                "audio/true-hd" -> "TrueHD"
                "audio/mp4a-latm" -> "AAC"
                "audio/flac" -> "FLAC"
                "audio/opus" -> "Opus"
                "audio/mpeg" -> "MP3"
                "audio/raw" -> "PCM"
                else -> null
            }
            codec?.let(parts::add)
        }
        return parts.distinct().joinToString(" · ")
    }
}
