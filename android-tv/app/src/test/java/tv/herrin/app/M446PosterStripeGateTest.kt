package tv.herrin.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class M446PosterStripeGateTest {
    @Test fun differentHashesDoNotWaitForUnrelatedImage() {
        val gates = PosterStripeGate(stripeCount = 2)
        assertTrue(gates.acquire(0))
        try {
            assertTrue("Unrelated poster should acquire another stripe immediately",
                gates.acquire(1))
            gates.release(1)
        } finally {
            gates.release(0)
        }
    }

    @Test fun cancelledWaiterDoesNotHoldAnIoThreadUntilOldDownloadFinishes() {
        val gates = PosterStripeGate(stripeCount = 1)
        assertTrue(gates.acquire(7))
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val acquired = AtomicBoolean(true)
        val waiter = Thread {
            started.countDown()
            val locked = gates.acquire(199, cancelled = { cancelled.get() })
            acquired.set(locked)
            if (locked) gates.release(199)
        }
        waiter.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // Give the worker time to encounter the occupied stripe.
            Thread.sleep(80)
            cancelled.set(true)
            waiter.join(1_500)
            assertFalse("Old off-screen work must abandon a contested stripe", waiter.isAlive)
            assertFalse("Cancelled worker must not take the stripe", acquired.get())
        } finally {
            gates.release(7)
            if (waiter.isAlive) { waiter.interrupt(); waiter.join(1_000) }
        }
        assertTrue(gates.acquire(199))
        gates.release(199)
    }

    @Test fun cancellationAfterAcquiringDoesNotLeakTheLock() {
        val gates = PosterStripeGate(stripeCount = 2)
        var checks = 0
        assertFalse(gates.acquire(1) { ++checks >= 3 })
        assertTrue("Lock must be immediately available after raced cancellation",
            gates.acquire(1))
        gates.release(1)
    }

    @Test fun previousHolderCompletesBeforeSecondRequestOfSameUrl() {
        val gates = PosterStripeGate(stripeCount = 1)
        assertTrue(gates.acquire(2))
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val acquired = AtomicBoolean(false)
        val waiter = Thread {
            started.countDown()
            if (gates.acquire(2)) {
                acquired.set(true)
                gates.release(2)
            }
            finished.countDown()
        }
        waiter.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse("Same URL cannot download twice simultaneously",
                finished.await(80, TimeUnit.MILLISECONDS))
        } finally {
            gates.release(2)
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(acquired.get())
        waiter.join(1_000)
    }
}
