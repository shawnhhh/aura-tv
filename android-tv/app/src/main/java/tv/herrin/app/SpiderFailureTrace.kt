package tv.herrin.app

import android.content.Context
import java.io.File

/** Safe Java stack structure only: never Throwable.message, URLs, request payloads or native dumps. */
internal object SpiderFailureTrace {
    private const val NAME="spider-failure-structure.txt"
    private val identifier=Regex("[A-Za-z0-9_.$<>-]{1,180}")
    internal fun structure(error:Throwable):String {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        val rows=mutableListOf<String>()
        var current:Throwable?=error
        repeat(4) {
            val cause=current?:return@repeat
            if(!seen.add(cause))return@repeat
            rows += "CAUSE " + cause.javaClass.name.takeIf(identifier::matches).orEmpty()
            cause.stackTrace.take(12).forEach { frame ->
                val owner=frame.className.takeIf(identifier::matches)?:return@forEach
                val method=frame.methodName.takeIf(identifier::matches)?:return@forEach
                rows += "$owner.$method:${frame.lineNumber.coerceIn(-1,1_000_000)}"
            }
            current=cause.cause
        }
        return rows.joinToString("\n").take(12_000)
    }
    fun record(context:Context,error:Throwable) {
        runCatching {
            val target=File(context.filesDir,NAME)
            val temp=File(context.filesDir,"$NAME.tmp")
            temp.writeText("${System.currentTimeMillis()}\n${SpiderStartupTrace.currentStage?.name.orEmpty()}\n${structure(error)}")
            check(temp.renameTo(target))
        }
    }
    fun recent(context:Context):String?=runCatching {
        val file=File(context.filesDir,NAME)
        if(!file.isFile ||file.length() !in 1..16_000)return@runCatching null
        val lines=file.readLines()
        if(System.currentTimeMillis()-(lines.firstOrNull()?.toLongOrNull()?:0) !in 0..120_000L)return@runCatching null
        lines.drop(1).joinToString("\n")
    }.getOrNull()
}
