package tv.herrin.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A small striped lock protects the same image URL against duplicate cache
 * reads/downloads. Unlike synchronized(), waiting for a stripe is cancellable:
 * a LazyRow item disposed by fast D-pad navigation does not waste an IO thread
 * waiting behind a slow unrelated image whose hash hits the same stripe.
 *
 * ReentrantLock is intentionally acquired/released on the same IO thread.
 * Do not suspend or change dispatchers between these calls.
 */
internal class PosterStripeGate(stripeCount: Int = DEFAULT_STRIPES) {
    companion object {
        const val DEFAULT_STRIPES = 128
        private const val WAIT_POLL_MS = 35L
    }

    private val locks: Array<ReentrantLock>

    init {
        require(stripeCount > 0)
        locks = Array(stripeCount) { ReentrantLock() }
    }

    private fun lock(key: Int): ReentrantLock =
        locks[(key and Int.MAX_VALUE) % locks.size]

    fun acquire(key: Int, cancelled: () -> Boolean = { false }): Boolean {
        val stripe = lock(key)
        if (cancelled()) return false
        try {
            while (!cancelled()) {
                if (!stripe.tryLock(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) continue
                if (!cancelled()) return true
                stripe.unlock()
                return false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    fun release(key: Int) {
        lock(key).unlock()
    }
}
