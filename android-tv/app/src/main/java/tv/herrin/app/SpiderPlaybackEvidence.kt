package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject

/** Fixed structural codes only. Never serializes plugin keys, values or exception messages. */
internal object SpiderPlaybackEvidence {
    private val events = ArrayDeque<Triple<String, String, String>>()
    @Synchronized fun clear() { events.clear() }
    @Synchronized private fun add(stage: String, code: String, detail: String = "") {
        if (events.size == 20) events.removeFirst()
        events.addLast(Triple(stage, code, detail))
    }
    fun structure(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root == null) { add("SPIDER", "RESPONSE_INVALID_JSON"); return }
        val kind = when (root.opt("url")) {
            is String -> "STRING"; is JSONArray -> "ARRAY"; is JSONObject -> "OBJECT"; else -> "MISSING"
        }
        add("SPIDER", "RESPONSE_URL_$kind")
        for (key in listOf("parse", "jx")) {
            val value = root.optInt(key, 0)
            add("SPIDER", "RESPONSE_" + key.uppercase(), "VALUE=" + value.coerceIn(0, 9))
        }
        add("SPIDER", when(root.opt("header")) {
            is String -> "HEADER_STRING"; is JSONObject -> "HEADER_OBJECT"; else -> "HEADER_FALLBACK"
        })
    }
    fun parser(type: Int) = add("SPIDER", "PARSER_SELECTED", "TYPE=" + type.coerceIn(0, 9))
    fun failure(error: Throwable) = add("SPIDER", "PARSER_CAUSE_" + when(error) {
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "TIMEOUT"
        is java.net.UnknownHostException -> "DNS"
        is javax.net.ssl.SSLException -> "TLS"
        is java.net.ConnectException -> "CONNECT"
        is ClassNotFoundException -> "CLASS"
        is NoSuchMethodException -> "ABI"
        is java.io.IOException -> "IO"
        else -> "OTHER"
    })
    @Synchronized fun snapshot() = JSONArray().apply {
        events.forEach { (stage, code, detail) -> put(JSONArray(listOf(stage, code, detail))) }
    }
    fun replay(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until minOf(array.length(), 20)) {
            val row = array.optJSONArray(i) ?: continue
            HerrinPlaybackDiagnosticJournal.record(row.optString(0), row.optString(1), row.optString(2))
        }
    }
}
