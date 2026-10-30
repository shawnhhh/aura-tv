package tv.herrin.app

/**
 * Bounded remote long-press seek accumulator.
 *
 * Android KeyEvent repeatCount > 0 means the same D-pad/media button is being
 * held. The old player ignored repeats entirely: one tap advanced 10 seconds
 * but holding the button did nothing. Sending an ExoPlayer seek for *every*
 * repeat can also produce costly decoder flushes and delayed picture.
 *
 * Commit the initial 10s tap immediately, batch repeat intent into at most
 * one seek per [REPEAT_COMMIT_MS], and flush the remaining intent on KeyUp.
 * The UI should render the returned, clamped real player position, never an
 * uncommitted preview as if a seek had already completed.
 *
 * Stateful only for the current player composition; no coroutine, global
 * mutable state, extra thread, or timer. Call on the Android UI thread.
 */
internal class RemoteHoldSeek {
    companion object {
        const val INITIAL_STEP_MS = 10_000L
        const val REPEAT_STEP_MS = 5_000L
        const val REPEAT_COMMIT_MS = 240L
        const val MAX_BATCH_MS = 30_000L
    }

    private var heldDirection = 0
    private var pendingMs = 0L
    private var lastCommitAtMs = 0L

    /**
     * @param direction -1 back / +1 forward
     * @param repeatCount Android native key repeat counter
     * @param eventTimeMs monotonic KeyEvent.eventTime
     * @return signed seek delta, or null when the repeat should be coalesced
     */
    fun down(direction: Int, repeatCount: Int, eventTimeMs: Long): Long? {
        require(direction == -1 || direction == 1)
        require(repeatCount >= 0)
        if (repeatCount == 0 || heldDirection != direction) {
            heldDirection = direction
            pendingMs = 0L
            lastCommitAtMs = eventTimeMs
            return INITIAL_STEP_MS * direction
        }
        pendingMs = (pendingMs + REPEAT_STEP_MS).coerceAtMost(MAX_BATCH_MS)
        if (eventTimeMs < lastCommitAtMs ||
            eventTimeMs - lastCommitAtMs < REPEAT_COMMIT_MS) return null

        val delta = pendingMs * direction
        pendingMs = 0L
        lastCommitAtMs = eventTimeMs
        return delta.takeIf { it != 0L }
    }

    fun up(direction: Int): Long? {
        require(direction == -1 || direction == 1)
        if (heldDirection != direction) return null
        val remaining = pendingMs * direction
        reset()
        return remaining.takeIf { it != 0L }
    }

    fun reset() {
        heldDirection = 0
        pendingMs = 0L
        lastCommitAtMs = 0L
    }
}
