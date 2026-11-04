/*
 * Derived from FongMi/TV player/media/PlaySpec.java at
 * 305dff233cde512e2c83f71529b7c3ecfd61fefc. Original author: FongMi.
 * GPL-3.0-only; see android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-04: explicit authorized host input and immutable per-attempt headers.
 * Fork-specific auth, ISO, timeshift, DRM and ending contracts are not represented
 * by PlaybackMedia; see COMPATIBILITY.md. No unsupported API is fabricated.
 */
package tv.herrin.app.nativeplayback.player

import androidx.media3.common.MediaMetadata
import tv.herrin.app.PlaybackMedia
import java.util.UUID

internal data class PlaySpec(
    val media: PlaybackMedia,
    val key: String = "herrin-${UUID.randomUUID()}",
    val metadata: MediaMetadata = MediaMetadata.EMPTY
) {
    val format: String? get() = media.declaredMime?.takeIf { it.isNotBlank() }
        ?: if (media.uri.contains("/proxy/mpd/")) "application/dash+xml" else null

    companion object {
        fun from(media: PlaybackMedia, uri: String = media.uri,
                 mime: String? = media.declaredMime, key: String = "herrin-${UUID.randomUUID()}") =
            PlaySpec(media.copy(uri = uri, declaredMime = mime,
                headers = media.headers.toMap(), subtitles = media.subtitles.toList()), key)
    }
}
