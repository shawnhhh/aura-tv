/*
 * M4.67 executable libmpv playback session. Does not replace the existing
 * Herrin Media3/TV UI; caller must explicitly create this view after loading
 * a matching native binary candidate in a separate opt-in test APK.
 * JNI/Surface conventions: FongMi/mpv-android@049ade89abc85fc05ef73dc9ba6f1810466c9f8a.
 */
package tv.herrin.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import `is`.xyz.mpv.MPVLib
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native mpv SurfaceView that can play an authorized progressive HTTP video
 * through FongMi's actual media3iso JNI stream callback.
 *
 * Requires a separate native-enabled APK and MUST NOT be instantiated by the
 * default Media3 player. No HLS/DASH/DRM or authenticated redirects here.
 */
internal class MpvNativePlaybackView(context: Context) :
    SurfaceView(context), SurfaceHolder.Callback, Closeable {

    interface Listener {
        fun onLoaded() {}
        fun onFailure(reason: Int, code: Int) {}
    }

    private val app = context.applicationContext
    private val lifecycleLock = Companion
    private var initialized = false
    private var attached = false
    private var closed = false
    private var sourceUri: String? = null
    private var loading = false
    @Volatile private var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Boolean) {}
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun event(eventId: Int) {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED)
                mainHandler.post { listener?.onLoaded() }
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
                // Upstream iterates a synchronized mutable observer list:
                // removing within its callback could trigger CME. Defer it.
                mainHandler.post {
                    MPVLib.removeObserver(this)
                    lifecycleLock.nativeOwner.set(false)
                }
            }
        }
        override fun eventEndFile(reason: Int, error: Int, errorString: String?) {
            if (error < 0) mainHandler.post { listener?.onFailure(reason, error) }
        }
    }

    fun start(uri: String, headers: Map<String, String> = emptyMap(),
              callback: Listener? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native surface must run on main thread" }
        check(!closed && !initialized) { "MPV session already started or closed" }
        val preflight = MpvNativeAvailability.inspect(context)
        check(preflight.available) {
            "MPV native ABI not packaged: " + preflight.missingLibraries.joinToString()
        }
        check(lifecycleLock.nativeOwner.compareAndSet(false, true)) {
            "Another MPV instance still owns native global player"
        }
        // Construct the stream before touching JNI: invalid source headers cannot
        // initialize a native player or leak credentials to a redirect target.
        val validated = try { MpvSecureHttpStream(uri, headers) }
        catch (error: Exception) {
            lifecycleLock.nativeOwner.set(false)
            throw error
        }
        validated.close()
        val id = "media3iso://herrin-" + UUID.randomUUID().toString()
        listener = callback
        sourceUri = id
        try {
            MPVLib.create(app)
            MPVLib.setOptionString("profile", "fast")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("hwdec", "mediacodec-copy")
            MPVLib.setOptionString("idle", "once")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.init()
            MPVLib.registerStream(id) { MpvSecureHttpStream(uri, headers) }
            MPVLib.addObserver(observer)
            initialized = true
            holder.addCallback(this)
            if (holder.surface.isValid) surfaceCreated(holder)
        } catch (error: Throwable) {
            runCatching { MPVLib.unregisterStream(id) }
            runCatching { MPVLib.removeObserver(observer) }
            runCatching { MPVLib.destroy() }
            lifecycleLock.nativeOwner.set(false)
            sourceUri = null
            throw error
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized || closed || attached || !holder.surface.isValid) return
        MPVLib.attachSurface(holder.surface)
        attached = true
        MPVLib.setOptionString("force-window", "yes")
        if (!loading) {
            val uri = sourceUri ?: return
            loading = true
            val result = MPVLib.command(arrayOf("loadfile", uri, "replace"))
            if (result < 0) listener?.onFailure(MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR, result)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attached && width > 0 && height > 0)
            MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!attached) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
        attached = false
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native surface must close on main thread" }
        if (closed) return
        closed = true
        holder.removeCallback(this)
        if (initialized) {
            if (attached) surfaceDestroyed(holder)
            sourceUri?.let { MPVLib.unregisterStream(it) }
            // Native destroy returns before the MPV shutdown event. The global
            // ownership guard clears only after receiving MPV_EVENT_SHUTDOWN.
            MPVLib.destroy()
        }
        sourceUri = null
        listener = null
        initialized = false
    }

    companion object {
        private val nativeOwner = AtomicBoolean(false)
    }
}
