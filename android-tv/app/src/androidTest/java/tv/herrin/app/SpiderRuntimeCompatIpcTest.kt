package tv.herrin.app

import android.app.ActivityManager
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
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SpiderRuntimeCompatIpcTest {
    private val site = TvBoxSite("合成站点", true, key = "synthetic", spiderApi = "csp_Synthetic")
    private val config = TvBoxConfig.Single(listOf(site), emptyList(), true)

    @Test fun staleCallbacksCannotInvalidateANewBinding() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = RecordingContext(instrumentation.targetContext)
        val client = TvBoxSpiderBridge(context)
        try {
            assertTrue(client.browse(site, config) is SpiderResult.Failure)
            val old = context.registrations.single()
            client.close()
            instrumentation.waitForIdleSync()
            assertTrue(client.browse(site, config) is SpiderResult.Failure)
            assertEquals(2, context.registrations.size)
            instrumentation.runOnMainSync {
                old.onServiceDisconnected(ComponentName(context, TvBoxSpiderService::class.java))
                old.onBindingDied(ComponentName(context, TvBoxSpiderService::class.java))
                old.onNullBinding(ComponentName(context, TvBoxSpiderService::class.java))
            }
            assertEquals("synthetic", (client.browse(site, config) as SpiderResult.Failure).message)
            assertEquals("Old callbacks must not force a third bind", 2, context.registrations.size)
            assertEquals(1, context.unbinds)
        } finally { client.close() }
    }

    @Test fun serviceUsesAnotherProcessAndRebindsAfterItsDeath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = DeathRecordingContext(instrumentation.targetContext)
        val client = TvBoxSpiderBridge(context)
        try {
            assertTrue(client.browse(site, config) is SpiderResult.Failure)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val process = manager.runningAppProcesses.first { it.processName == "${context.packageName}:spider" }
            assertNotEquals(Process.myPid(), process.pid)
            Process.killProcess(process.pid)
            withTimeout(10_000) { context.disconnected.await() }
            assertTrue(client.browse(site, config) is SpiderResult.Failure)
            val replacement = manager.runningAppProcesses.first {
                it.processName == "${context.packageName}:spider" && it.pid != process.pid
            }
            assertNotEquals("A fresh service process must be bound", process.pid, replacement.pid)
            assertTrue("UI process remains alive", Process.myPid() > 0)
        } finally { client.close() }
    }

    private class DeathRecordingContext(base: Context) : ContextWrapper(base) {
        val disconnected = CompletableDeferred<Unit>()
        private val registrations = mutableMapOf<ServiceConnection, ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val wrapper = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) =
                    connection.onServiceConnected(name, binder)
                override fun onServiceDisconnected(name: ComponentName?) {
                    connection.onServiceDisconnected(name)
                    disconnected.complete(Unit)
                }
                override fun onBindingDied(name: ComponentName?) = connection.onBindingDied(name)
                override fun onNullBinding(name: ComponentName?) = connection.onNullBinding(name)
            }
            registrations[connection] = wrapper
            return super.bindService(intent, wrapper, flags)
        }
        override fun unbindService(connection: ServiceConnection) {
            registrations.remove(connection)?.let { super.unbindService(it) }
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val registrations = mutableListOf<ServiceConnection>()
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        private var firstReply: Pair<Messenger, Int>? = null
        private val server = Messenger(Handler(Looper.getMainLooper()) { request ->
            // A delayed reply from the previous session must not complete the new request.
            firstReply?.let { (reply, oldId) ->
                reply.send(Message.obtain(null, request.what).apply {
                    arg1 = oldId
                    data = Bundle().apply { putString("payload", SpiderIpc.error("stale").toString()) }
                })
            }
            if (firstReply == null) firstReply = request.replyTo to request.arg1
            request.replyTo.send(Message.obtain(null, request.what).apply {
                arg1 = request.arg1
                data = Bundle().apply { putString("payload", SpiderIpc.error("synthetic").toString()) }
            })
            true
        })
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            registrations += connection
            connection.onServiceConnected(ComponentName(this, TvBoxSpiderService::class.java), server.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
}
