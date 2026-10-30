package tv.herrin.app

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Reserve a single independent lane for the active movie's full-screen hero.
 *
 * Four generic download permits let a cold 60-poster shelf consume all slots,
 * delaying a freshly selected movie backdrop for up to a network timeout.
 * Keep three poster permits and one hero permit instead. Total concurrency
 * remains at four, with no extra threads, processes, or unrestricted prefetch.
 *
 * This synchronous bridge is also used by instrumentation caches. A caller
 * can pass its Compose coroutine Job's liveness as [cancelled], allowing jobs
 * discarded during rapid remote navigation to stop waiting for a permit.
 */
internal class PosterRequestBudget(
    tileCapacity: Int = TILE_CONCURRENCY,
    heroCapacity: Int = HERO_CONCURRENCY
) {
    companion object {
        const val TILE_CONCURRENCY = 3
        const val HERO_CONCURRENCY = 1
        private const val WAIT_POLL_MS = 35L
    }

    private val tiles: Semaphore
    private val heroes: Semaphore

    init {
        require(tileCapacity > 0 && heroCapacity > 0)
        tiles = Semaphore(tileCapacity, true)
        heroes = Semaphore(heroCapacity, true)
    }

    fun acquire(hero: Boolean, cancelled: () -> Boolean = { false }): Boolean {
        val permits = if (hero) heroes else tiles
        if (cancelled()) return false
        try {
            while (!cancelled()) {
                if (!permits.tryAcquire(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) continue
                if (!cancelled()) return true
                permits.release()
                return false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    fun release(hero: Boolean) {
        (if (hero) heroes else tiles).release()
    }

    internal fun freeSlots(hero: Boolean): Int =
        (if (hero) heroes else tiles).availablePermits()
}
