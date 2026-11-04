/*
 * Derived from FongMi/TV player/media/MediaItemFactory.java, pinned
 * 305dff233cde512e2c83f71529b7c3ecfd61fefc. Original author: FongMi.
 * GPL-3.0-only; see android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-04: official Media3 1.9.4, explicit authorized PlaybackMedia input;
 * retains media key, metadata, image duration, request headers and subtitle IDs.
 */
package tv.herrin.app.nativeplayback.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import tv.herrin.app.FongMiPlaybackItemSupport
import tv.herrin.app.FongMiSubtitleFormats
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object MediaItemFactory {
    fun from(spec: PlaySpec): MediaItem {
        val media = spec.media
        val flags = FongMiPlaybackItemSupport.subtitleSelectionFlags(media.subtitles.map { it.language })
        return MediaItem.Builder()
            .setUri(media.uri)
            .setMediaId(spec.key)
            .setMediaMetadata(spec.metadata)
            .setImageDurationMs(15_000)
            .setMimeType(spec.format)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(media.uri))
                .setExtras(Bundle().apply { media.headers.forEach { (name, value) -> putString(name, value) } })
                .build())
            .setSubtitleConfigurations(media.subtitles.mapIndexed { index, subtitle ->
                val uri = Uri.parse(subtitle.uri)
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setId("external:" + UUID.nameUUIDFromBytes(subtitle.uri.toByteArray(StandardCharsets.UTF_8)))
                    .setMimeType(FongMiSubtitleFormats.mimeType(uri.path))
                    .setLanguage(subtitle.language).setLabel(subtitle.title)
                    .setSelectionFlags(flags[index]).build()
            }).build()
    }
}
