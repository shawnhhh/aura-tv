/*
 * Derived from FongMi/TV fongmi@5e191aaced194e5cac5668cc91908ecb65336222:
 * player/exo/{ExoUtil,ExoMediaSourceFactory}.java, player/media/MediaItemFactory.java.
 * Original author: FongMi. GPL-3.0; see android-tv/LICENSE-GPL-3.0.md.
 * Pinned contract comparison: 305dff233cde512e2c83f71529b7c3ecfd61fefc.
 * 2026-11-04: pinned upstream comparison; official Media3 1.9.4, explicit host
 * settings and per-attempt source creation through nativeplayback.player.
 * This ports the compatible source paths; it does NOT claim the unavailable patched
 * Media3/libass/mpv ABI has been supplied. See docs/m467/player-engine.md.
 */
package tv.herrin.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.fongmi.android.tv.player.exo.ExoMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object FongMiExoPlayerFactory {
    /** Host settings replace upstream App/PlayerSetting/DecodeSetting globals. */
    data class Settings(
        val bufferMultiplier: Int = 1,
        val preferAac: Boolean = false,
        val tunneling: Boolean = false,
        val preferredTextLanguages: List<String> = listOf("zh", "zho", "chi"),
        // Retains Herrin's real vendor-codec fallback while the native module is absent.
        // This tries installed platform codecs and must never be advertised as FFmpeg.
        val platformDecoderFallback: Boolean = true
    ) {
        init { require(bufferMultiplier in 1..10) }
    }

    fun create(context: Context, media: PlaybackMedia, settings: Settings = Settings()): ExoPlayer {
        val appContext = context.applicationContext
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(settings.platformDecoderFallback)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(true)
        val selector = DefaultTrackSelector(appContext)
        selector.setParameters(selector.buildUponParameters().apply {
            setForceHighestSupportedBitrate(true)
            setTunnelingEnabled(settings.tunneling)
            setPreferredTextLanguages(*settings.preferredTextLanguages.toTypedArray())
            if (settings.preferAac) setPreferredAudioMimeType("audio/mp4a-latm")
        }.build())
        val buffer = settings.bufferMultiplier
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .setBufferDurationsMsForLocalPlayback(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS)
            .build()
        return ExoPlayer.Builder(appContext, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory(appContext, media))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                playWhenReady = true
            }
    }

    fun mediaSourceFactory(context: Context, media: PlaybackMedia): ExoMediaSourceFactory {
        // Request credentials remain per playback session. A global shared factory or
        // an upstream shared URL-only cache would cross Herrin's authorization boundary.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(androidx.media3.common.util.Util.getUserAgent(context, "tv.herrin.app"))
            .setAllowCrossProtocolRedirects(media.allowHttpRedirects)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(media.headers)
        return ExoMediaSourceFactory(DefaultDataSource.Factory(context, http))
    }

    fun mediaItem(media: PlaybackMedia, uri: String = media.uri,
                  mimeOverride: String? = media.declaredMime): MediaItem {
        return tv.herrin.app.nativeplayback.player.MediaItemFactory.from(
            tv.herrin.app.nativeplayback.player.PlaySpec.from(media, uri, mimeOverride))
    }
}
