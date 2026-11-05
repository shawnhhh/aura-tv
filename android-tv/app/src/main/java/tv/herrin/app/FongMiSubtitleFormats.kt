/*
 * Derived from FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222
 * player/track/TrackUtil.java#getSubtitleMimeType. Original author: FongMi.
 * GPL-3.0; see android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-01: Kotlin/host ABI adapter; MIME decisions are unchanged.
 */
package tv.herrin.app

import com.fongmi.android.tv.player.track.TrackUtil

internal object FongMiSubtitleFormats {
    fun mimeType(path: String?): String = TrackUtil.getSubtitleMimeType(path)

    /** Only real published Media3 subtitle decoders; URL validation stays at the source boundary. */
    fun supportedMime(url: String, declared: String?): String? {
        val mime = declared.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        if (mime in setOf("text/vtt", "application/x-subrip", "text/x-ssa", "application/ttml+xml")) return mime
        if (mime.isNotEmpty() && mime != "application/octet-stream") return null
        val path = url.substringBefore('?').substringBefore('#').lowercase(java.util.Locale.ROOT)
        if (listOf(".vtt", ".srt", ".ass", ".ssa", ".ttml", ".xml", ".dfxp").none(path::endsWith)) return null
        return mimeType(path)
    }

    fun selectionFlag(raw: Int): Int = raw and 7 // Media3 DEFAULT | FORCED | AUTOSELECT only.
}
