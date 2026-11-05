/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc PlayerManager.java:
 * start/startMediaItem, beginPlaybackAttempt, listener, controls and release.
 * Original author: FongMi. HERRIN 2026-11-05 host adaptation: injected real Exo,
 * main Handler in place of App, isolated ParseJob, no MPV/disc/custom-fork stubs.
 */
package com.fongmi.android.tv.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.fongmi.android.tv.player.media.MediaItemFactory
import com.fongmi.android.tv.player.media.PlaySpec
import java.util.UUID

/** Owns the sole decoder. UI renders this player's events/surface, never a second engine. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerManager(val player: ExoPlayer) : AutoCloseable {
    private val handler = Handler(player.applicationLooper)
    private var released = false
    private var timeoutMs = 15_000L
    private var spec: PlaySpec? = null
    var playbackId: String = UUID.randomUUID().toString(); private set
    var playbackAttempt = 0L; private set
    var videoSize: VideoSize = VideoSize.UNKNOWN; private set
    var tracks: Tracks = Tracks.EMPTY; private set
    var lastError: PlaybackException? = null; private set
    var onTimeout: ((PlaybackException) -> Unit)? = null

    private val timeout = Runnable {
        if (!released && player.playbackState == Player.STATE_BUFFERING) {
            player.stop()
            val error = PlaybackException("Playback loading deadline",
                java.net.SocketTimeoutException(), PlaybackException.ERROR_CODE_TIMEOUT)
            lastError = error
            onTimeout?.invoke(error)
        }
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            handler.removeCallbacks(timeout)
            if (state == Player.STATE_BUFFERING && player.playWhenReady)
                handler.postDelayed(timeout, timeoutMs)
        }
        override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
            if (!ready) handler.removeCallbacks(timeout)
            else if (player.playbackState == Player.STATE_BUFFERING) {
                handler.removeCallbacks(timeout); handler.postDelayed(timeout, timeoutMs)
            }
        }
        override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
        override fun onTracksChanged(value: Tracks) { tracks = value }
        override fun onPlayerError(error: PlaybackException) {
            lastError = error; handler.removeCallbacks(timeout)
        }
    }
    init { player.addListener(listener) }

    fun start(next: PlaySpec, startPositionMs: Long = C.TIME_UNSET, timeout: Long = 15_000L) {
        check(!released) { "PlayerManager released" }
        require(timeout > 0)
        if (spec?.key != next.key) { playbackId = UUID.randomUUID().toString(); playbackAttempt = 0 }
        playbackAttempt++
        spec = next; timeoutMs = timeout; lastError = null
        handler.removeCallbacks(this.timeout)
        val item = MediaItemFactory.from(next)
        if (startPositionMs == C.TIME_UNSET) player.setMediaItem(item)
        else player.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
        // Upstream startMediaItem arms every attempt, including replacement
        // while STATE_BUFFERING itself does not change.
        if (player.playbackState == Player.STATE_BUFFERING) {
            handler.removeCallbacks(this.timeout)
            handler.postDelayed(this.timeout, timeoutMs)
        }
    }

    fun play() { check(!released); player.play() }
    fun pause() { check(!released); handler.removeCallbacks(timeout); player.pause() }
    fun seekTo(positionMs: Long) { check(!released); player.seekTo(positionMs.coerceAtLeast(0L)) }
    fun seekToDefaultPosition() { check(!released); player.seekToDefaultPosition() }
    fun stop() { check(!released); handler.removeCallbacks(timeout); player.stop() }

    override fun close() {
        if (released) return
        released = true
        handler.removeCallbacks(timeout)
        onTimeout = null
        player.removeListener(listener)
        player.stop()
        player.release()
        spec = null
    }
}
