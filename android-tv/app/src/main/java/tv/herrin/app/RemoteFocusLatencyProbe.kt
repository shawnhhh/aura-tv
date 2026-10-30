package tv.herrin.app

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Opt-in, local-only performance probe for Android TV instrumentation.
 *
 * Measures from Activity key dispatch to Compose Poster.onFocusChanged. This
 * measures focus callback latency, NOT display scan-out or photon latency.
 * Disabled in normal playback, no persisted titles, URLs, or credentials.
 */
internal object RemoteFocusLatencyProbe {
    data class Sample(val keyCode: Int, val filmId: String, val elapsedNanos: Long) {
        val millis: Double get() = elapsedNanos / 1_000_000.0
    }

    @Volatile private var measuring = false
    private var pendingAtNanos = 0L
    private var pendingKeyCode = 0
    private val observations = ArrayList<Sample>(128)

    @Synchronized fun start() {
        pendingAtNanos = 0L
        pendingKeyCode = 0
        observations.clear()
        measuring = true
    }

    @Synchronized fun stop(): List<Sample> {
        measuring = false
        pendingAtNanos = 0L
        pendingKeyCode = 0
        return observations.toList().also { observations.clear() }
    }

    fun keyDown(keyCode: Int) {
        if (!measuring) return
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT &&
            keyCode != KeyEvent.KEYCODE_DPAD_UP &&
            keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return
        synchronized(this) {
            if (!measuring) return
            pendingAtNanos = SystemClock.elapsedRealtimeNanos()
            pendingKeyCode = keyCode
        }
    }

    fun posterFocused(filmId: String) {
        if (!measuring) return
        synchronized(this) {
            if (!measuring || pendingAtNanos == 0L) return
            val duration = SystemClock.elapsedRealtimeNanos() - pendingAtNanos
            if (duration >= 0L) observations.add(Sample(pendingKeyCode, filmId, duration))
            pendingAtNanos = 0L
        }
    }
}
