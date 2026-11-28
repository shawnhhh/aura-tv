package tv.herrin.app

import tv.herrin.kernel.SeekBounds

/** One remote gesture: a tap commits 10s; a hold previews by elapsed time and commits once on release. */
internal class RemoteHoldSeek {
    companion object {
        const val INITIAL_STEP_MS = 10_000L
        const val PREVIEW_MS_PER_SECOND = 10_000L
        private const val ACCELERATION_DELAY_SECONDS = 1.5
        private const val ACCELERATION_RAMP_SECONDS = 4.0
        private const val MAX_PREVIEW_MS_PER_SECOND = 120_000L
    }
    private var direction = 0
    private var downTime = 0L
    private var repeatAt: Long? = null
    private var origin = 0L
    private var duration = 0L
    val isHeld get() = direction != 0
    val isLongPress get() = repeatAt != null
    fun owns(value: Int) = direction == value

    /** Returns true only when this gesture becomes a long press. Orphan repeat events do nothing. */
    fun down(value: Int, repeatCount: Int, eventTime: Long, position: Long, duration: Long,
             physicalDownTime: Long = eventTime): Boolean {
        require(value == -1 || value == 1)
        require(repeatCount >= 0)
        if (repeatCount == 0 && (!isHeld || downTime != physicalDownTime || direction != value)) {
            direction = value; downTime = physicalDownTime; repeatAt = null
            origin = position.coerceAtLeast(0); this.duration = duration
            return false
        }
        if (!isHeld || direction != value || downTime != physicalDownTime) return false
        if (repeatAt != null) return false
        origin = position.coerceAtLeast(0); this.duration = duration
        repeatAt = eventTime
        return true
    }
    fun preview(now: Long): Long? {
        val began = repeatAt ?: return null
        val seconds = (now - began).coerceAtLeast(0) / 1000.0
        val accelerating = (seconds - ACCELERATION_DELAY_SECONDS).coerceAtLeast(0.0)
        val t = (accelerating / ACCELERATION_RAMP_SECONDS).coerceAtMost(1.0)
        // Integral of smoothstep speed. Speed and acceleration join smoothly
        // at both ends of the ramp and never depend on the repeat frequency.
        val ramp = ACCELERATION_RAMP_SECONDS * (t * t * t - 0.5 * t * t * t * t)
        val cruise = (accelerating - ACCELERATION_RAMP_SECONDS).coerceAtLeast(0.0)
        val distance = seconds * PREVIEW_MS_PER_SECOND +
            (MAX_PREVIEW_MS_PER_SECOND - PREVIEW_MS_PER_SECOND) * (ramp + cruise)
        val delta = distance.toLong() * direction
        return SeekBounds.offset(origin, delta, duration)
    }
    fun up(value: Int, now: Long, position: Long, duration: Long, physicalDownTime: Long? = null): Long? {
        require(value == -1 || value == 1)
        if (direction != value || now < downTime || physicalDownTime != null && downTime != physicalDownTime) return null
        val target = preview(now) ?: SeekBounds.offset(position, INITIAL_STEP_MS * direction, duration)
        reset()
        return target
    }
    fun reset() { direction = 0; downTime = 0; repeatAt = null; origin = 0; duration = 0 }
}
