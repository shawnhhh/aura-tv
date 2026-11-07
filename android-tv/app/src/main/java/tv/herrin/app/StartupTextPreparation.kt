package tv.herrin.app

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.AnnotatedString

/** Public Compose API only. The captured trace showed SaversKt class verification
 * in a long main-thread traversal. Prepare its serializers while the independent
 * vector wordmark is drawing, before publishing preferences/home readiness. */
internal fun prepareStartupText() {
    val started=android.os.SystemClock.uptimeMillis()
    android.os.Trace.beginSection("HerrinTextPrepare")
    try {
        val scope=object:SaverScope { override fun canBeSaved(value:Any)=true }
        val prepared=with(AnnotatedString.Saver) { with(scope) { save(AnnotatedString("HERRIN"))!=null } }
        android.util.Log.i("HerrinStartupWarmup","text_prepare_ms=${android.os.SystemClock.uptimeMillis()-started} ready=$prepared main_thread=${android.os.Looper.myLooper()==android.os.Looper.getMainLooper()}")
    } finally { android.os.Trace.endSection() }
}
