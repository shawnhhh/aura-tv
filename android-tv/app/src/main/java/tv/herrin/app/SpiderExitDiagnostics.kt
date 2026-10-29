package tv.herrin.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Android 11+ supplies an OS-owned process exit record after a crashed
 * :spider service. Never access or expose the private tombstone/stack trace:
 * native backtraces, JAR output and exit descriptions may contain credentials.
 */
internal object SpiderExitDiagnostics {
    private const val MAX_AGE_MS = 90_000L

    fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生组件崩溃（JNI/so）"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 未捕获异常"
        ApplicationExitInfo.REASON_ANR -> "插件进程无响应"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足而终止"
        ApplicationExitInfo.REASON_SIGNALED -> "进程被信号终止"
        ApplicationExitInfo.REASON_EXIT_SELF -> "插件进程主动退出"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "系统资源使用限制"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "进程初始化失败"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
        else -> "系统记录的其他退出原因"
    }

    fun recent(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching null
            val exited = manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .filter { it.processName == context.packageName + ":spider" }
                .filter { (nowMs - it.timestamp) in 0..MAX_AGE_MS }
                .maxByOrNull { it.timestamp } ?: return@runCatching null
            reasonLabel(exited.reason)
        }.getOrNull()
    }
}
