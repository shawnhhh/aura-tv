/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Original local Binder fixture; no remote plugin or JAR is loaded. */
class SpiderLoaderLifecycleTest {
    @Test fun browseQueuedBeforeItsFirstMainDispatchCannotLoadAfterRevocation() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fixture = FixtureContext(inst.targetContext)
        val bridge = TvBoxSpiderBridge(fixture)
        val dispatcher = PausingDispatcher()
        val site = TvBoxSite("Original fixture", true, key = "queued", spiderApi = "csp_Fixture")
        val pending = async(dispatcher) {
            bridge.browse(site, TvBoxConfig.Single(listOf(site), emptyList(), true))
        }
        try {
            dispatcher.runNext() // browse entered, first main-thread dispatch still pending
            bridge.close()
            inst.waitForIdleSync()
            while (!pending.isCompleted) {
                dispatcher.runNext()
                inst.waitForIdleSync()
            }
            assertTrue(pending.await() is SpiderResult.Failure)
            assertNull(bridge.approvedActiveSiteKey())
            assertEquals("revoked queued apply must not bind a loader service", 0, fixture.binds)
            assertEquals(0, fixture.calls)
        } finally {
            pending.cancel(); bridge.close(); dispatcher.release(); pending.join()
            inst.waitForIdleSync()
        }
    }

    @Test fun deliveredBrowseCannotReauthorizeAfterCloseAndFreshApplyStillWorks() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fixture = FixtureContext(inst.targetContext)
        val bridge = TvBoxSpiderBridge(fixture)
        val dispatcher = PausingDispatcher()
        val site = TvBoxSite("Original loader fixture", true, key = "first", spiderApi = "csp_Fixture")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true)
        val pending = async(dispatcher) { bridge.browse(site, config) }
        try {
            // Stop exactly after Binder has delivered success, but before the
            // caller continuation can publish its remembered source.
            while (fixture.replied.count > 0) {
                dispatcher.runNext()
                inst.waitForIdleSync()
            }
            bridge.close()
            assertNull("revocation must be immediate", bridge.approvedActiveSiteKey())
            inst.waitForIdleSync()
            while (!pending.isCompleted) {
                dispatcher.runNext()
                inst.waitForIdleSync()
            }
            assertTrue("late success must be rejected", pending.await() is SpiderResult.Failure)
            assertNull(bridge.approvedActiveSiteKey())
            assertTrue(bridge.search(site, "fixture") is SpiderResult.Failure)
            assertEquals("revoked source must not be replayed", 1, fixture.calls)
            assertEquals(1, fixture.binds)
            assertEquals(1, fixture.unbinds)

            val replacement = site.copy(key = "second")
            assertTrue(bridge.browse(replacement, config.copy(sites = listOf(replacement))) is SpiderResult.Ready)
            assertEquals("second", bridge.approvedActiveSiteKey())
            assertEquals(listOf("first", "second"), fixture.sites)
            assertEquals(2, fixture.binds)
            bridge.close(); bridge.close()
            inst.waitForIdleSync()
            assertEquals("repeated close must not unbind twice", 2, fixture.unbinds)
            assertNull(bridge.approvedActiveSiteKey())
        } finally {
            pending.cancel(); bridge.close(); dispatcher.release(); pending.join()
            inst.waitForIdleSync()
        }
    }

    private class PausingDispatcher : CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()
        private var released = false
        @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (released) Dispatchers.Default.dispatch(context, block) else queued.add(block)
        }
        @Synchronized fun release() {
            released = true
            while (true) {
                val task = queued.poll() ?: break
                Dispatchers.Default.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
            }
        }
        fun runNext() {
            val task = queued.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("expected a loader continuation")
            task.run()
        }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val replied = CountDownLatch(1)
        var binds = 0
        var unbinds = 0
        var calls = 0
        val sites = mutableListOf<String>()
        private val server = Messenger(Handler(Looper.getMainLooper()) { request ->
            calls++
            val payload = JSONObject(request.data.getString("payload").orEmpty())
            sites.add(payload.getJSONObject("site").getString("key"))
            val reply = JSONObject().put("ok", true).put("films", JSONArray())
            request.replyTo.send(Message.obtain(null, request.what).apply {
                arg1 = request.arg1
                data = Bundle().apply { putString("payload", reply.toString()) }
            })
            replied.countDown()
            true
        })
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            connection.onServiceConnected(ComponentName(this, TvBoxSpiderService::class.java), server.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
}
