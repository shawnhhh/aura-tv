package tv.herrin.app

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class M444PosterRequestBudgetTest {
    @Test fun coldShelfCannotOccupyTheHeroLane() {
        val budget = PosterRequestBudget(tileCapacity = 3, heroCapacity = 1)
        repeat(3) { assertTrue(budget.acquire(hero = false)) }
        assertEquals(0, budget.freeSlots(hero = false))
        assertEquals(1, budget.freeSlots(hero = true))
        assertTrue("Hero never waits behind the three poster downloads",
            budget.acquire(hero = true))
        assertEquals(0, budget.freeSlots(hero = true))
        repeat(3) { budget.release(hero = false) }
        budget.release(hero = true)
        assertEquals(3, budget.freeSlots(hero = false))
        assertEquals(1, budget.freeSlots(hero = true))
    }

    @Test fun abandonedTileWaiterStopsWithoutLeakingPermits() {
        val budget = PosterRequestBudget(tileCapacity = 1, heroCapacity = 1)
        assertTrue(budget.acquire(hero = false))
        val cancelled = AtomicBoolean(false)
        val acquired = AtomicBoolean(true)
        val waiter = Thread {
            acquired.set(budget.acquire(hero = false, cancelled = { cancelled.get() }))
        }
        waiter.start()
        try {
            cancelled.set(true)
            waiter.join(1_500)
            assertFalse("Cancelled image worker should not be stuck in a blocking acquire",
                waiter.isAlive)
            assertFalse("An abandoned poster must not consume a network slot",
                acquired.get())
            assertEquals(0, budget.freeSlots(hero = false))
        } finally {
            budget.release(hero = false)
            if (waiter.isAlive) { waiter.interrupt(); waiter.join(1_000) }
        }
        assertEquals(1, budget.freeSlots(hero = false))
    }

    @Test fun cancellationAfterSemaphoreAcquireReturnsPermit() {
        val budget = PosterRequestBudget(tileCapacity = 1, heroCapacity = 1)
        var checks = 0
        // First checks allow requesting the slot, final check cancels after acquire.
        val accepted = budget.acquire(hero = true) { ++checks >= 3 }
        assertFalse(accepted)
        assertEquals(1, budget.freeSlots(hero = true))
        assertEquals(1, budget.freeSlots(hero = false))
    }

    @Test fun cancelledBeforeAcquiringDoesNotReserveAnySlot() {
        val budget = PosterRequestBudget(tileCapacity = 2, heroCapacity = 1)
        assertFalse(budget.acquire(hero = true) { true })
        assertEquals(1, budget.freeSlots(hero = true))
        assertEquals(2, budget.freeSlots(hero = false))
    }
}
