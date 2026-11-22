package tv.herrin.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real same-:spider-process window for provider code using CatVod Runtime.activity().
 * It renders no QR, login state or media. Only the provider owns those operations. */
class SpiderRuntimeActivity : ComponentActivity() {
    private var ticket: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ticket = intent.getStringExtra(EXTRA_TICKET)
        if (!SpiderRuntimeActivityHost.attach(ticket, this)) { finish(); return }
        setContentView(View(this))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { SpiderRuntimeActivityHost.cancel(ticket, this@SpiderRuntimeActivity) }
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && window.decorView.windowToken != null) SpiderRuntimeActivityHost.ready(ticket, this)
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) SpiderRuntimeActivityHost.cancel(ticket, this)
    }
    override fun onDestroy() {
        SpiderRuntimeActivityHost.cancel(ticket, this)
        super.onDestroy()
    }
    companion object { internal const val EXTRA_TICKET = "spider.activity.ticket" }
}

/** Operation-scoped capability, never an Application cast or cross-process Activity object. */
internal object SpiderRuntimeActivityHost {
    private class Owner(val operation: Int, val request: (String) -> Unit, val cancel: () -> Unit) {
        val ticket = UUID.randomUUID().toString()
        val result = CompletableFuture<Activity>()
        var requested = false
        var activity = WeakReference<Activity>(null)
    }
    private val lock = Any()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var owner: Owner? = null

    fun begin(operation: Int, request: (String) -> Unit, cancel: () -> Unit) {
        val previous = synchronized(lock) { owner.also { owner = Owner(operation, request, cancel) } }
        previous?.let(::finish)
    }
    fun end(operation: Int? = null) {
        val previous = synchronized(lock) {
            owner?.takeIf { operation == null || it.operation == operation }?.also { owner = null }
        }
        previous?.let(::finish)
    }
    private fun finish(value: Owner) {
        value.result.completeExceptionally(IllegalStateException("Spider activity operation ended"))
        main.post { value.activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish() }
    }

    fun activity(): Activity? {
        val current = synchronized(lock) { owner } ?: run {
            SpiderNetworkTrace.event("ACTIVITY_NO_ACTIVE_OPERATION"); return null
        }
        current.activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
            // The real window must have completed attachment before first use.
            if (current.result.isDone && !current.result.isCompletedExceptionally) return it
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Blocking this looper would prevent the requested Activity attaching.
            SpiderNetworkTrace.event("ACTIVITY_REQUIRES_WORKER_THREAD"); return null
        }
        val start = synchronized(lock) {
            if (owner !== current) return null
            if (current.requested) false else { current.requested = true; true }
        }
        if (start) try {
            current.request(current.ticket)
            SpiderNetworkTrace.event("ACTIVITY_HOST_REQUESTED")
        } catch (_: Exception) {
            SpiderNetworkTrace.event("ACTIVITY_HOST_SEND_FAILED"); end(current.operation); return null
        }
        return try {
            current.result.get(10, TimeUnit.SECONDS).takeUnless { it.isFinishing || it.isDestroyed }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            SpiderNetworkTrace.event("ACTIVITY_HOST_INTERRUPTED"); end(current.operation); null
        } catch (_: java.util.concurrent.TimeoutException) {
            SpiderNetworkTrace.event("ACTIVITY_HOST_TIMEOUT"); end(current.operation); null
        } catch (_: java.util.concurrent.ExecutionException) {
            SpiderNetworkTrace.event("ACTIVITY_HOST_UNAVAILABLE"); null
        }
    }
    fun denied(operation: Int, ticket: String?) {
        val current = synchronized(lock) { owner?.takeIf { it.operation == operation && it.ticket == ticket } } ?: return
        SpiderNetworkTrace.event("ACTIVITY_FOREGROUND_HOST_DENIED")
        end(current.operation)
    }
    fun attach(ticket: String?, activity: Activity): Boolean = synchronized(lock) {
        val current = owner?.takeIf { it.requested && it.ticket == ticket && !it.result.isCompletedExceptionally }
            ?: return false
        if (current.activity.get()?.let { it !== activity } == true) return false
        current.activity = WeakReference(activity)
        true
    }
    fun ready(ticket: String?, activity: Activity) = synchronized(lock) {
        owner?.takeIf { it.ticket == ticket && it.activity.get() === activity }?.let {
            if (it.result.complete(activity)) SpiderNetworkTrace.event("ACTIVITY_REAL_WINDOW_READY")
        }
    }
    fun cancel(ticket: String?, activity: Activity) {
        val current = synchronized(lock) {
            owner?.takeIf { it.ticket == ticket && it.activity.get() === activity }?.also { owner = null }
        } ?: return
        finish(current)
        current.cancel()
    }
}
