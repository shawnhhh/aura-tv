/* SPDX-License-Identifier: GPL-3.0-only
 * Herrin host preferences for FongMi primary track restoration. No stream IDs or credentials.
 */
package tv.herrin.app

import android.content.Context
import androidx.media3.common.C
import com.fongmi.android.tv.bean.Track

internal class PlaybackTrackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("herrin-track-preferences", Context.MODE_PRIVATE)
    fun save(track: Track) {
        if (track.type !in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) return
        val lang = track.language.orEmpty().lowercase()
        if (track.isSelected && (lang == "und" || !lang.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")))) return
        prefs.edit().putBoolean("${track.type}:off", !track.isSelected)
            .putString("${track.type}:language", lang).apply()
    }
    fun load(): List<Track> = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT).mapNotNull { type ->
        val lang = prefs.getString("$type:language", null) ?: return@mapNotNull null
        Track(type, "", "").apply { language = lang; isSelected = !prefs.getBoolean("$type:off", false) }
    }
}
