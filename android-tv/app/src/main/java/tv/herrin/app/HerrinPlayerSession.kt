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
    // Scene callbacks may outlive the warehouse that created this playback
    // ticket. Track the currently selected source session independently of
    // the process-wide shared FongMi player lease.
    private var wholeSourceSession = media.kernelPlayback?.session
    val manager = Controls()
    init {
        if (legacy != null) runtime?.registerLegacy(this, ::close)
        else if (original != null) requireNotNull(runtime).claimWholePlayback(this)
    }
    private fun isActiveWhole() = original != null && !closed &&
        requireNotNull(runtime).ownsWholePlayback(this)

    fun select(media: PlaybackMedia, position: Long): java.util.concurrent.CompletionStage<Void>? {
        check(!closed)
        if (whole && !isActiveWhole())
            return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Retired playback UI lease"))
        this.media = media
        val ticket = media.kernelPlayback
        if (whole) wholeSourceSession = ticket?.session
        if (ticket != null && !ticket.replay && runtime?.pages?.alreadyPlaying(ticket) == true)
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        return start(media.uri, media.declaredMime, position)
    }
    fun start(uri: String, mime: String? = media.declaredMime, position: Long = C.TIME_UNSET): java.util.concurrent.CompletionStage<Void>? {
        check(!closed)
        if (original != null) {
            if (!isActiveWhole())
                return java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Retired playback UI lease"))
            val ticket = requireNotNull(media.kernelPlayback)
            requireNotNull(runtime).player(ticket) // Recheck session ownership.
            return runtime.gateway.play(ticket.episode, position)
        } else { requireNotNull(legacy).start(uri, mime, position); return null }
    }
    fun observe(observer: (Kernel.State) -> Unit) {
        val sourceAtSubscription = wholeSourceSession
        if (isActiveWhole()) requireNotNull(runtime).gateway.observe {
            if (isActiveWhole() && sourceAtSubscription == runtime.pages?.playbackLease()) {
                runtime.pages?.onState(it)
                observer(it)
            }
        }
    }
    fun bind(view: PlayerView?) {
        if (original != null) {
            if (isActiveWhole()) original.bind(view)
        } else if (!closed && view != null) view.player = player
    }
    fun play() { if (isActiveWhole()) requireNotNull(runtime).gateway.resume() else if (!whole && !closed) requireNotNull(legacy).play() }
    fun unbind(view: PlayerView) {
        if (original != null) {
            if (isActiveWhole()) original.unbind(view)
        } else view.player = null
    }
    fun pause() { if (isActiveWhole()) requireNotNull(runtime).gateway.pause() else if (!whole && !closed) requireNotNull(legacy).pause() }
    fun snapshot() { if (isActiveWhole()) requireNotNull(runtime).gateway.snapshot() }
    override fun close() {
        if (closed) return
        closed = true
        if (original != null) {
            val owner = requireNotNull(runtime)
            // Compose may dispose the previous panel after its replacement exists.
            // Only the current lease can detach the shared Surface or stop the core.
            if (owner.releaseWholePlayback(this) &&
                owner.pages?.playbackLease() == wholeSourceSession) {
                original.bind(null)
                owner.gateway.observe { owner.pages?.onState(it) }
                owner.gateway.stop()
            }
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
            if (!isActiveWhole()) return player.currentPosition
            val destination = (player.currentPosition + delta).coerceAtLeast(0)
            requireNotNull(runtime).gateway.seek(destination)
            return destination
        }
        fun stop() { if (isActiveWhole()) requireNotNull(runtime).gateway.stop() else if (!whole && !closed) requireNotNull(legacy).manager.stop() }
        fun setSpeed(value: Float): Float = if (whole) {
            if (isActiveWhole()) requireNotNull(original).speed(value) else value
        } else requireNotNull(legacy).manager.setSpeed(value)
        fun cycleTrack(type: Int): Boolean = if (whole) {
            isActiveWhole() && requireNotNull(original).cycleTrack(type)
        } else requireNotNull(legacy).manager.cycleTrack(type)
        fun setRepeatOne(value: Boolean) { if (isActiveWhole()) requireNotNull(original).repeatOne(value) else if (!whole && !closed) requireNotNull(legacy).manager.setRepeatOne(value) }
        fun setSkipSilenceEnabled(value: Boolean) { if (isActiveWhole()) requireNotNull(original).skipSilence(value) else if (!whole && !closed) requireNotNull(legacy).manager.setSkipSilenceEnabled(value) }
    }
}
