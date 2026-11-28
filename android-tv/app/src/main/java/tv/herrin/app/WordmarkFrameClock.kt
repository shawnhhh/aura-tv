package tv.herrin.app

/** First-install shader/class loading and a covered window are not animation time. */
internal class WordmarkFrameClock(initialElapsedMs: Float = 0f) {
    private var previous: Long? = null
    var elapsedMs = initialElapsedMs
        private set

    fun frame(now: Long, drawable: Boolean): Float {
        if (!drawable) { previous = null; return elapsedMs }
        val last = previous
        previous = now
        // A stalled frame must not consume an entire reveal/brighten phase.
        // Normal vsync keeps the original V13 durations; only missed time is
        // discarded. Nothing runs faster to catch up after first-install work.
        if (last != null) elapsedMs += ((now - last).coerceAtLeast(0) / 1_000_000f).coerceAtMost(64f)
        return elapsedMs
    }
}
