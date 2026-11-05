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
import com.fongmi.android.tv.bean.Track
import com.fongmi.android.tv.player.track.TrackUtil
import tv.herrin.app.PlaybackTransport
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
    var onDiagnostic: ((String, String) -> Unit)? = null
    private val selectedTracks = mutableMapOf<Int, Track>()
    private var restoringTracks = false
    private var speedBeforePress: Float? = null

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
            onDiagnostic?.invoke("PLAYBACK_STATE", "STATE=$state")
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
        override fun onTracksChanged(value: Tracks) {
            tracks = value
            if (!value.isEmpty) restoreTrackSelection()
            onDiagnostic?.invoke("TRACKS_CHANGED", "AUDIO=${if (value.isTypeSelected(C.TRACK_TYPE_AUDIO)) 1 else 0}")
        }
        override fun onRenderedFirstFrame() { onDiagnostic?.invoke("FIRST_FRAME", "") }
        override fun onPlayerError(error: PlaybackException) {
            lastError = error; handler.removeCallbacks(timeout)
        }
    }
    init { player.addListener(listener) }

    fun start(next: PlaySpec, startPositionMs: Long = C.TIME_UNSET, timeout: Long = 15_000L) {
        check(!released) { "PlayerManager released" }
        require(timeout > 0)
        if (spec?.key != next.key) {
            playbackId = UUID.randomUUID().toString(); playbackAttempt = 0
            if (spec != null) resetTrack()
        }
        endSpeedPress()
        playbackAttempt++
        spec = next; timeoutMs = timeout; lastError = null
        handler.removeCallbacks(this.timeout)
        val item = MediaItemFactory.from(next)
        onDiagnostic?.invoke("PLAYBACK_INITIALIZING", "ATTEMPT=${playbackAttempt.coerceAtMost(99999)}")
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
    fun stop() { check(!released); handler.removeCallbacks(timeout); endSpeedPress(); player.stop() }

    // Published Media3 supports these controls directly. The fork's audio/text
    // offsets, secondary subtitles and disc APIs are deliberately not emulated.
    fun setSpeed(speed: Float): Float {
        check(!released)
        require(speed.isFinite())
        if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
            player.playbackParameters = player.playbackParameters.withSpeed(speed.coerceIn(0.1f, 5.0f))
        return player.playbackParameters.speed
    }
    fun startSpeedPress(speed: Float): Boolean {
        check(!released)
        require(speed.isFinite())
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return false
        if (speedBeforePress == null) speedBeforePress = player.playbackParameters.speed
        setSpeed(speed)
        return true
    }
    fun endSpeedPress() {
        check(!released)
        val speed = speedBeforePress ?: return
        speedBeforePress = null
        setSpeed(speed)
    }
    fun setRepeatOne(repeat: Boolean) {
        check(!released)
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    fun setSkipSilenceEnabled(enabled: Boolean) { check(!released); player.skipSilenceEnabled = enabled }
    fun replay(positionMs: Long) {
        check(!released)
        if (positionMs == C.TIME_UNSET) seekToDefaultPosition() else seekTo(positionMs)
        play()
    }
    fun seekBy(deltaMs: Long): Long { check(!released); return PlaybackTransport.seekBy(player, deltaMs) }
    fun cycleTrack(type: Int): Boolean {
        check(!released)
        return PlaybackTransport.cycle(player, type, ::setTrack)
    }
    fun setTrack(track: Track): Boolean {
        check(!released)
        if (track.isSecondary || track.type !in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) return false
        if (track.isSelected && TrackUtil.findSelection(player, track) == null) return false
        // Snapshot the mutable upstream value; never keep a UI-owned Track.
        selectedTracks[track.type] = Track(track.type, track.name, track.format).apply {
            label = track.label; language = track.language; mimeType = track.mimeType
            ordinal = track.ordinal; isSelected = track.isSelected
        }
        restoreTrackSelection()
        return true
    }
    fun resetTrack() { check(!released); selectedTracks.clear(); TrackUtil.reset(player) }

    private fun restoreTrackSelection() {
        if (restoringTracks || selectedTracks.isEmpty()) return
        restoringTracks = true
        try {
            val current = player.trackSelectionParameters
            val builder = current.buildUpon()
            for ((type, track) in selectedTracks) {
                builder.clearOverridesOfType(type).setTrackTypeDisabled(type, !track.isSelected)
                if (track.isSelected) TrackUtil.findSelection(player, track)?.let(builder::setOverrideForType)
                // Missing tracks remain pending for the next manifest update;
                // never substitute an unrelated language by ordinal.
            }
            val next = builder.build()
            if (next != current) player.trackSelectionParameters = next
        } finally { restoringTracks = false }
    }

    override fun close() {
        if (released) return
        endSpeedPress()
        released = true
        handler.removeCallbacks(timeout)
        onTimeout = null
        onDiagnostic?.invoke("PLAYBACK_RELEASED", "")
        onDiagnostic = null
        selectedTracks.clear()
        player.removeListener(listener)
        player.stop()
        player.release()
        spec = null
    }
}
