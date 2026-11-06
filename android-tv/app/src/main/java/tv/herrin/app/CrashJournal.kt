package tv.herrin.app

import android.content.Context
import java.io.File

/** No throwable messages, source URLs, thread names, titles or user input are persisted. */
internal object SafeCrashSummary {
    fun format(error: Throwable): String {
        val out = StringBuilder(4096)
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(4) {
            val cause = current ?: return@repeat
            if (!seen.add(cause)) { current = null; return@repeat }
            out.append("type=").append(identifier(cause.javaClass.name)).append('\n')
            cause.stackTrace.take(20).forEach { frame ->
                out.append(" at ").append(identifier(frame.className)).append('.')
                    .append(identifier(frame.methodName)).append(':').append(frame.lineNumber).append('\n')
            }
            current = cause.cause
        }
        return out.take(10000).toString()
    }
    private fun identifier(value: String) = value.takeIf {
        it.length <= 180 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.$<>-" }
    } ?: "redacted"
}

internal object CrashJournal {
    @Volatile private var installed = false
    @Volatile private var phase = "IDLE"
    @Volatile private var location = "browse/首页"
    @Volatile private var memory = "memory=not_sampled"
    @Volatile var previous: String = "上次 Java 异常：尚无脱敏记录"
        private set
    @Volatile private var imageFailures = 0
    fun stage(value: String) {
        if (value == "IMAGE_OOM") { imageFailures++; return }
        if (value in setOf("IDLE","PROBE","CACHE","DISCOVERY","DETAIL","SCRAPE","PUBLISH","IMAGE_OOM")) phase = value
    }
    fun location(screen: String, category: String) {
        val screens = setOf("browse","detail","player","live-player","settings","search","facets","sites")
        val categories = setOf("首页","电影","剧集","动漫","综艺","纪录片","Top 250","我的","直播")
        location = "${screen.takeIf { it in screens } ?: "other"}/${category.takeIf { it in categories } ?: "other"}"
    }
    fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        memory = "heap_used=${runtime.totalMemory()-runtime.freeMemory()} heap_max=${runtime.maxMemory()} " +
            "image_jobs=${AuthorizedPosterCache.activeTasks} bitmap_cache=${AuthorizedPosterCache.memoryBytes} image_oom=$imageFailures"
    }
    fun install(context: Context) {
        if (installed) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler() ?: return
        val file = File(context.filesDir, "last-safe-crash.txt")
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            .takeIf { it == context.packageName || it == context.packageName + ":spider" } ?: "other" else "api26-process"
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Bounded local write only. Snapshot was collected before the exception.
                val text = "JAVA_UNCAUGHT time=${System.currentTimeMillis()} process=$process\n" +
                    "stage=$phase location=$location $memory\n" + SafeCrashSummary.format(error)
                file.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (_: Throwable) { /* Preserve Android's original fatal handling, including OOM. */ }
            finally { previousHandler.uncaughtException(thread, error) }
        }
        installed = true
    }
    /** Only invoked on IO before the normal home is composed. */
    fun loadPrevious(context: Context) {
        val file = File(context.filesDir, "last-safe-crash.txt")
        previous = runCatching { if (file.isFile && file.length() in 1..16000) file.readText()
            else "上次 Java 异常：尚无脱敏记录" }.getOrDefault("上次 Java 异常：记录不可读取")
    }
}

class HerrinApplication : android.app.Application() {
    override fun onCreate() { super.onCreate(); CrashJournal.install(this) }
}
