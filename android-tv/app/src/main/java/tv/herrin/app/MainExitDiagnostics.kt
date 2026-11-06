package tv.herrin.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** OS-owned finite exit reason and memory counters only, never a crash description/trace/URL. */
internal object MainExitDiagnostics {
    fun recent(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
        return try {
            val last = manager.getHistoricalProcessExitReasons(context.packageName,0,8)
                .filter { it.processName == context.packageName }.maxByOrNull { it.timestamp } ?: return ""
            "\nAPP_EXIT_TIME=${last.timestamp} APP_EXIT_REASON=${last.reason}(${when (last.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                else -> "OTHER"
            }}) PSS_KB=${last.pss} RSS_KB=${last.rss}"
        } catch (_: SecurityException) { "\nAPP_EXIT_UNAVAILABLE" }
    }
}
