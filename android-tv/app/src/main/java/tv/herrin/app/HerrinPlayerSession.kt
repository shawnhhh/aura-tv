/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import tv.herrin.kernel.Kernel

/** Keeps the approved playback chrome independent of who owns the decoder. */
internal class HerrinPlayerSession(context: Context, private var media: PlaybackMedia,
                                   persistTrackPreferences: Boolean) : AutoCloseable {
    private val runtime = HerrinKernelRuntime.find()
    private val original = media.kernelPlayback?.let { requireNotNull(runtime).player(it) }
    private val legacy = if (original == null) {
        runtime?.retireWholePlaybackForPersonal()
        HerrinPlaybackAdapter(context, media, persistTrackPreferences = persistTrackPreferences)
    } else null
    val whole get() = original != null
    val isClosed get() = closed
    val skipSilenceEnabled get() = original?.skipSilenceEnabled ?: requireNotNull(legacy).player.skipSilenceEnabled
    val player: Player get() = original?.player ?: requireNotNull(legacy).player
    private var closed = false
    val manager = Controls()
    init { if (legacy != null) runtime?.registerLegacy(this, ::close) }

    fun select(media: PlaybackMedia, position: Long): java.util.concurrent.CompletionStage<Void>? {
        check(!closed)
        this.media = media
        val ticket = media.kernelPlayback
        if (ticket != null && !ticket.replay && runtime?.pages?.alreadyPlaying(ticket) == true)
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        return start(media.uri, media.declaredMime, position)
    }
    fun start(uri: String, mime: String? = media.declaredMime, position: Long = C.TIME_UNSET): java.util.concurrent.CompletionStage<Void>? {
        check(!closed)
        if (original != null) {
            val ticket = requireNotNull(media.kernelPlayback)
            requireNotNull(runtime).player(ticket) // Recheck session ownership.
            return runtime.gateway.play(ticket.episode, position)
        } else { requireNotNull(legacy).start(uri, mime, position); return null }
    }
    fun observe(observer: (Kernel.State) -> Unit) { if (whole) requireNotNull(runtime).gateway.observe {
        runtime.pages?.onState(it)
        observer(it)
    } }
    fun bind(view: PlayerView?) {
        if (closed) { view?.player = null; return }
        if (original != null) original.bind(view) else if (view != null) view.player = player
    }
    fun play() { if (whole) requireNotNull(runtime).gateway.resume() else requireNotNull(legacy).play() }
    fun unbind(view: PlayerView) { if (original != null && !closed) original.unbind(view) else view.player = null }
    fun pause() { if (!closed) { if (whole) requireNotNull(runtime).gateway.pause() else requireNotNull(legacy).pause() } }
    fun snapshot() { if (whole && !closed) requireNotNull(runtime).gateway.snapshot() }
    override fun close() {
        if (closed) return
        closed = true
        if (original != null) {
            original.bind(null)
            val owner = requireNotNull(runtime)
            owner.gateway.observe { owner.pages?.onState(it) }
            owner.gateway.stop()
        } else {
            requireNotNull(legacy).close()
            runtime?.unregisterLegacy(this)
        }
    }
    inner class Controls {
        var onTimeout: ((PlaybackException) -> Unit)?
            get() = legacy?.manager?.onTimeout
            set(value) { check(!whole); requireNotNull(legacy).manager.onTimeout = value }
        fun seekBy(delta: Long): Long {
            if (!whole) return requireNotNull(legacy).manager.seekBy(delta)
            val destination = (player.currentPosition + delta).coerceAtLeast(0)
            requireNotNull(runtime).gateway.seek(destination)
            return destination
        }
        fun stop() { if (whole) requireNotNull(runtime).gateway.stop() else requireNotNull(legacy).manager.stop() }
        fun setSpeed(value: Float) = original?.speed(value) ?: requireNotNull(legacy).manager.setSpeed(value)
        fun cycleTrack(type: Int) = original?.cycleTrack(type) ?: requireNotNull(legacy).manager.cycleTrack(type)
        fun setRepeatOne(value: Boolean) { if (original != null) original.repeatOne(value) else requireNotNull(legacy).manager.setRepeatOne(value) }
        fun setSkipSilenceEnabled(value: Boolean) { if (original != null) original.skipSilence(value) else requireNotNull(legacy).manager.setSkipSilenceEnabled(value) }
    }
}
