package tv.herrin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Interface shapes only. Never reads response bodies, headers, URLs or plugin exception messages. */
internal object SpiderProxyDiagnostics {
    private const val NAME = "spider-proxy-shapes.json"

    fun shape(value: Any?, failure: Throwable?): JSONObject = JSONObject().apply {
        if (failure != null) {
            put("outcome", "CALLBACK_THROW")
            put("exception_type", failure.javaClass.name.take(180))
        } else if (value == null) put("outcome", "CALLBACK_NULL")
        else if (value is Array<*>) {
            put("outcome", "CALLBACK_ARRAY")
            put("length", value.size)
            put("types", JSONArray(value.take(5).map { it?.javaClass?.name?.take(180) ?: "null" }))
            val code = value.firstOrNull() as? Number
            if (code != null) put("status", code.toInt().coerceIn(0, 999))
        } else {
            put("outcome", "CALLBACK_OTHER")
            put("type", value.javaClass.name.take(180))
        }
    }

    /** Observability must preserve return and exception identity, including recorder failure. */
    fun <T> observe(record: (Any?, Throwable?) -> Unit, block: () -> T): T = try {
        block().also { value -> runCatching { record(value, null) } }
    } catch (failure: Throwable) {
        runCatching { record(null, failure) }
        throw failure
    }

    fun <T> observe(context: Context, block: () -> T): T = observe({ value, failure ->
        persist(context, shape(value, failure))
        if (failure != null) SpiderFailureTrace.record(context, failure)
    }, block)

    @Synchronized private fun persist(context: Context, row: JSONObject) {
        val file = File(context.filesDir, NAME)
        val previous = runCatching { JSONArray(file.readText()) }.getOrNull() ?: JSONArray()
        val retained = JSONArray()
        for (index in maxOf(0, previous.length() - 15) until previous.length()) retained.put(previous.get(index))
        retained.put(row)
        file.writeText(retained.toString())
    }
}
