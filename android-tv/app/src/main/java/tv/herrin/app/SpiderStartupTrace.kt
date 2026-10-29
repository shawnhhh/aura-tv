package tv.herrin.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Tiny cross-process breadcrumb for native crashes, including SIGSEGV where
 * the isolated Spider process cannot send an IPC error. No URLs, credentials,
 * JAR contents or site extensions are written to disk.
 */
internal object SpiderStartupTrace {
    enum class Stage(val display: String) {
        DOWNLOAD("下载 JAR"), NATIVE("准备 ARM 原生库"), LOADER("建立 DEX 类加载器"),
        INITIALIZER("执行 Init.init"), CONSTRUCT("创建 Spider"),
        SPIDER_INIT("执行 Spider.init"), HOME("读取影片目录"),
        DETAILS("读取影片详情"), PLAYER("获取播放地址"),
        WATCHDOG("Spider 执行超时，宿主终止无响应子进程"), READY("插件已就绪")
    }

    private const val MAX_AGE_MS = 2 * 60 * 1_000L
    private const val NAME = "spider-startup-stage.txt"

    fun mark(context: Context, stage: Stage) {
        runCatching { write(File(context.filesDir, NAME), stage, System.currentTimeMillis()) }
    }

    fun recent(context: Context): String? = runCatching {
        read(File(context.filesDir, NAME), System.currentTimeMillis())
    }.getOrNull()

    internal fun write(file: File, stage: Stage, atMillis: Long) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write("$atMillis|${stage.name}".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        check(temp.renameTo(file)) { "无法保存 Spider 阶段信息" }
    }

    internal fun read(file: File, nowMillis: Long): String? {
        if (!file.isFile || file.length() !in 1..80) return null
        val parts = file.readText(Charsets.US_ASCII).split('|', limit = 2)
        if (parts.size != 2) return null
        val age = nowMillis - (parts[0].toLongOrNull() ?: return null)
        if (age !in 0..MAX_AGE_MS) return null
        val stage = Stage.entries.firstOrNull { it.name == parts[1] } ?: return null
        return stage.display.takeUnless { stage == Stage.READY }
    }
}
