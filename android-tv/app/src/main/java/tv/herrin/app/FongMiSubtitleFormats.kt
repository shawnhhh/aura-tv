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
}
