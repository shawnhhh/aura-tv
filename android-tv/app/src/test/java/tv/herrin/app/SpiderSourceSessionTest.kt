/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only original fixture source descriptors; never downloads or executes a JAR. */
class SpiderSourceSessionTest {
    private val first = "original-fixture:site-a:csp_Fixture"
    private val second = "original-fixture:site-b:csp_Fixture"

    @Test fun appliedSourceIsReplacedWithoutMixingTickets() {
        val state = SpiderSourceSession()
        val a = state.beginBrowse(state.snapshot())!!
        assertTrue(state.publish(a, first))
        assertEquals(first, state.descriptor())
        val b = state.beginBrowse(state.snapshot())!!
        assertNull(state.descriptor())
        assertFalse(state.isCurrent(a))
        assertTrue(state.publish(b, second))
        assertFalse("old site must not overwrite its replacement", state.publish(a, first))
        assertEquals(second, state.descriptor())
    }

    @Test fun revokedSessionRejectsLateOriginalFixtureCompletion() {
        val state = SpiderSourceSession()
        val ticket = state.beginBrowse(state.snapshot())!!
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val late = worker.submit<Boolean> {
                waiting.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                state.publish(ticket, first)
            }
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            state.revoke()
            release.countDown()
            assertFalse("revoked browse must not publish", late.get(3, TimeUnit.SECONDS))
            assertNull(state.descriptor())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun queuedApplyFromBeforeRevocationCannotStartLoading() {
        val state = SpiderSourceSession()
        val queued = state.snapshot()
        state.revoke()
        assertNull("queued old source must be rejected before execution", state.beginBrowse(queued))
        assertNull(state.descriptor())
    }

    @Test fun repeatedRevocationLeavesNoAuthorityAndAllowsOnlyANewExplicitApply() {
        val state = SpiderSourceSession()
        val old = state.beginBrowse(state.snapshot())!!
        state.publish(old, first)
        state.revoke(); state.revoke()
        assertNull(state.descriptor())
        assertFalse(state.isCurrent(old))
        val fresh = state.beginBrowse(state.snapshot())!!
        assertTrue(state.publish(fresh, second))
        assertEquals(second, state.descriptor())
    }

    @Test fun failedNewApplyCannotReusePreviousAppliedSource() {
        val state = SpiderSourceSession()
        val old = state.beginBrowse(state.snapshot())!!
        state.publish(old, first)
        state.beginBrowse(state.snapshot()) // no successful loader completion
        assertNull(state.descriptor())
        assertFalse(state.isCurrent(old))
    }
}
