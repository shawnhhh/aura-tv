/* SPDX-License-Identifier: GPL-3.0-only
 * Ported from FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc
 * player/media/MediaItemFactory.java. Original author: FongMi.
 * HERRIN 2026-11-05: inject resolved host model/settings; published Media3 ABI.
 * ISO editions, fork adblock and inline ClearKey require the real custom fork;
 * they are not replaced with stub types or advertised by this HTTP media port.
 */
package com.fongmi.android.tv.player.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import tv.herrin.app.FongMiPlaybackItemSupport
import tv.herrin.app.FongMiSubtitleFormats
import tv.herrin.app.PlaybackMedia
import java.nio.charset.StandardCharsets
import java.util.UUID

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object MediaItemFactory {
    @JvmStatic fun validateHeaders(headers: Map<*, *>) {
        require(headers.all { (key, value) -> key is String && value is String && key.isNotBlank() &&
            key.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' } }) {
            "Invalid media request headers"
        }
    }

    @JvmStatic fun buildMetadata(title: String, artist: String, artwork: String?): MediaMetadata =
        MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setSubtitle(if (title.isEmpty() || title == artist) "" else artist)
            .setArtworkUri(artwork?.takeIf(String::isNotBlank)?.let(Uri::parse)).build()

    fun from(spec: PlaySpec): MediaItem {
        val media = spec.media
        validateHeaders(media.headers)
        val uri = Uri.parse(spec.url)
        require(uri.scheme?.lowercase() in setOf("https", "http", "file")) { "Unresolved media URI" }
        if (uri.scheme != "file") require(uri.host != null && uri.userInfo == null) { "Invalid media URI" }
        val extras = Bundle().apply { media.headers.forEach { (key, value) -> putString(key, value) } }
        val flags = FongMiPlaybackItemSupport.subtitleSelectionFlags(media.subtitles.map { it.language },
            rawFlags = media.subtitles.map { it.rawFlag })
        return MediaItem.Builder().setUri(uri).setMediaId(spec.key)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
            .setMediaMetadata(spec.metadata).setMimeType(spec.format)
            .setImageDurationMs(15_000)
            .setSubtitleConfigurations(media.subtitles.mapIndexedNotNull { index, sub ->
                val subUri = Uri.parse(sub.uri)
                MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setId("external:" + UUID.nameUUIDFromBytes(sub.uri.toByteArray(StandardCharsets.UTF_8)))
                    .setLabel(sub.title).setLanguage(sub.language)
                    .setMimeType(FongMiSubtitleFormats.supportedMime(sub.uri, sub.mimeType)
                        ?: return@mapIndexedNotNull null)
                    .setSelectionFlags(flags[index]).build()
            }).build()
    }
}

/** The resolved HTTP branch of upstream PlaySpec; Parser/Proxy already ran in :spider. */
data class PlaySpec(val key: String, val media: PlaybackMedia, val url: String = media.uri,
    val format: String? = media.declaredMime,
    val metadata: MediaMetadata = MediaMetadata.EMPTY) {
    init { require(key.isNotBlank()) }
}
