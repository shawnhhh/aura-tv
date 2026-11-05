/* SPDX-License-Identifier: GPL-3.0-only
 * HERRIN production lifecycle adapter for pinned FongMi/TV 305dff2 PlayerManager.
 */
package tv.herrin.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.fongmi.android.tv.player.PlayerManager
import com.fongmi.android.tv.player.media.MediaItemFactory
import com.fongmi.android.tv.player.media.PlaySpec

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class HerrinPlaybackAdapter(context: Context, media: PlaybackMedia,
        private val mediaId: String = media.mediaId ?: java.util.UUID.randomUUID().toString()) : AutoCloseable {
    val media = media.copy(headers = media.headers.toMap(), subtitles = media.subtitles.toList())
    val manager = PlayerManager(FongMiExoPlayerFactory.create(context, this.media))
        .apply { onDiagnostic = { event, detail -> HerrinPlaybackDiagnosticJournal.record("MEDIA3", event, detail) } }
    val player get() = manager.player
    fun item(uri: String = media.uri, mime: String? = media.declaredMime): MediaItem =
        MediaItemFactory.from(PlaySpec(mediaId, media, uri, mime))
    fun start(uri: String = media.uri, mime: String? = media.declaredMime, position: Long = C.TIME_UNSET) =
        manager.start(PlaySpec(mediaId, media, uri, mime), position)
    fun play() = manager.play()
    fun pause() = manager.pause()
    fun seekTo(position: Long) = manager.seekTo(position)
    fun prepareRemote(mediaId: String, url: String, mime: String?) {
        tv.herrin.app.nativeplayback.FongMiPlaybackAdapter.requireResolvedMediaUrl(url)
        manager.start(PlaySpec(mediaId, media, url, mime))
    }
    override fun close() = manager.close()

    companion object {
        @JvmStatic fun createRemote(context: Context, mediaId: String, url: String,
                headers: Map<String, String>, mime: String?): HerrinPlaybackAdapter {
            tv.herrin.app.nativeplayback.FongMiPlaybackAdapter.requireResolvedMediaUrl(url)
            return HerrinPlaybackAdapter(context, PlaybackMedia(url, headers.toMap(), declaredMime = mime), mediaId)
        }
    }
}
