package tv.herrin.app

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Durable candidate and bounded progress. A phone save never replaces the working root. */
internal class LanSourceImport(context: Context) {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    class Pending(val id: String, val url: String, val state: String, val code: String)
    fun current(): Pending? = synchronized(lock) {
        val raw = prefs.getString("phone_source_import", null) ?: return@synchronized null
        val json = JSONObject(raw)
        Pending(json.getString("id"), json.getString("url"), json.getString("state"), json.optString("code"))
    }
    fun save(url: String): Pending = synchronized(lock) {
        val pending = Pending(UUID.randomUUID().toString(), url, "SAVED", "")
        write(pending); pending
    }
    fun update(id: String, state: String, code: String = ""): Boolean = synchronized(lock) {
        val pending = current()?.takeIf { it.id == id } ?: return@synchronized false
        write(Pending(id, pending.url, state, code)); true
    }
    fun isActive(id: String): Boolean = synchronized(lock) {
        val pending = current() ?: return@synchronized false
        pending.id == id && pending.state == "ACTIVE" &&
            prefs.getString("tvbox_warehouse_url", "") == pending.url
    }
    fun activate(id: String): Boolean = synchronized(lock) {
        val pending = current()?.takeIf { it.id == id } ?: return@synchronized false
        val edit = prefs.edit().putString("tvbox_warehouse_url", pending.url)
        if (prefs.getString("tvbox_warehouse_url", "") != pending.url) {
            edit.putString("tvbox_trusted_spider_root", "").putString("tvbox_trusted_spider_jar", "")
        }
        check(edit.putString("phone_source_import", encode(Pending(id, pending.url, "ACTIVE", ""))).commit())
        true
    }
    fun status(id: String): JSONObject {
        val pending = current()?.takeIf { it.id == id }
        return JSONObject().put("importId", id).put("state", pending?.state ?: "SUPERSEDED")
            .put("code", pending?.code.orEmpty())
    }
    private fun encode(p: Pending) = JSONObject().put("id", p.id).put("url", p.url)
        .put("state", p.state).put("code", p.code).toString()
    private fun write(p: Pending) { check(prefs.edit().putString("phone_source_import", encode(p)).commit()) }
    companion object {
        private val lock = Any()
        fun failure(reason: String): String = when {
            "HTTP 403" in reason -> "SOURCE_HTTP_403"
            "WAREHOUSE_HTTP_ERROR" in reason -> "SOURCE_HTTP_ERROR"
            "WAREHOUSE_PARSE_FAILED" in reason -> "SOURCE_WAREHOUSE_PARSE_FAILED"
            "WAREHOUSE_FORMAT_INVALID" in reason -> "SOURCE_BODY_INVALID"
            "WAREHOUSE_URL_INVALID" in reason -> "SOURCE_URL_INVALID"
            "WAREHOUSE_" in reason -> "SOURCE_FETCH_FAILED"
            else -> "SOURCE_WAREHOUSE_PARSE_FAILED"
        }
    }
}
