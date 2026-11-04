/*
 * Derived from FongMi/TV player/PlayerManager.java at
 * 305dff233cde512e2c83f71529b7c3ecfd61fefc. Original author: FongMi.
 * GPL-3.0-only; see android-tv/LICENSE-GPL-3.0.md.
 * 2026-11-04 Herrin adaptation: Exo-only official Media3 1.9.4, explicit host
 * Context, authorized PlaySpec, Android Handler instead of App globals, one host
 * MediaSession, foreground timeout cancellation. Parse/recovery remain host-owned.
 * See COMPATIBILITY.md for the exact unsupported upstream contracts.
 */
package tv.herrin.app.nativeplayback.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import tv.herrin.app.PlaybackMedia
import java.net.SocketTimeoutException
import java.util.UUID

/** Owns one actual Exo engine/session. All operations run on the player's main looper. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlayerManager(context: Context, media: PlaybackMedia) {
    private val context = context.applicationContext
    private val engine = ExoPlayerEngine(this.context, media)
    val player get() = engine.player
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = LoadDeadline()
    private val key = "herrin-${UUID.randomUUID()}"
    private val media = media.copy(headers = media.headers.toMap(), subtitles = media.subtitles.toList())
    private var timeoutMs = 15_000L
    private var timer: Runnable? = null
    private var session: MediaSession? = null
    private var foreground = true
    private var released = false
    var onTimeout: ((PlaybackException) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (foreground && player.playWhenReady) armDeadline()
                Player.STATE_IDLE, Player.STATE_READY, Player.STATE_ENDED -> cancelDeadline()
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) cancelDeadline()
            else if (foreground && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && timer == null) armDeadline()
        }
        override fun onPlayerError(error: PlaybackException) { cancelDeadline() }
    }

    init { player.addListener(listener) }

    fun spec(uri: String = media.uri, mime: String? = media.declaredMime) =
        PlaySpec.from(media, uri, mime, key)

    fun start(spec: PlaySpec, startPositionMs: Long = C.TIME_UNSET,
              playWhenReady: Boolean = true, timeoutMs: Long = 15_000L) {
        check(!released) { "Player manager released" }
        require(timeoutMs > 0)
        cancelDeadline()
        this.timeoutMs = timeoutMs
        // Creation belongs to committed start, after Compose disposes the old session.
        if (session == null) session = MediaSession.Builder(context, player).setId(key).build()
        engine.start(spec, startPositionMs, playWhenReady && foreground)
        if (foreground && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && timer == null) armDeadline()
    }

    fun setForeground(value: Boolean) {
        if (released) return
        foreground = value
        if (!value) {
            cancelDeadline()
            player.pause()
        } else if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) armDeadline()
    }

    private fun armDeadline() {
        cancelDeadline()
        val attempt = deadline.begin(SystemClock.elapsedRealtime(), timeoutMs)
        val callback = Runnable {
            if (!released && foreground && player.playbackState == Player.STATE_BUFFERING &&
                deadline.isExpired(attempt, SystemClock.elapsedRealtime())) {
                stop()
                onTimeout?.invoke(PlaybackException("Host playback loading deadline",
                    SocketTimeoutException(), PlaybackException.ERROR_CODE_TIMEOUT))
            }
        }
        timer = callback
        handler.postDelayed(callback, timeoutMs)
    }

    private fun cancelDeadline() {
        timer?.let(handler::removeCallbacks)
        timer = null
        deadline.cancel()
    }

    fun stop() {
        if (released) return
        cancelDeadline()
        engine.stop()
    }

    fun release() {
        if (released) return
        released = true
        cancelDeadline()
        onTimeout = null
        session?.release()
        session = null
        player.removeListener(listener)
        engine.stop()
        engine.release()
    }
}
