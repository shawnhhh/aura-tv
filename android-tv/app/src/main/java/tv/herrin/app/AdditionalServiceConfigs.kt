package tv.herrin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Additional entries in the existing herrin preferences, consumed directly by business adapters.
 * The original remote-editable primary slots remain authoritative and are never copied here.
 */
internal class AdditionalServiceConfigs(private val context: Context) {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    data class Entry(val id: String, val name: String, val url: String, val epg: String, val enabled: Boolean)
    fun list(module: String): List<Entry> {
        require(module in setOf("sources", "live", "nas"))
        return runCatching {
            val array = JSONArray(prefs.getString("services_$module", "[]"))
            (0 until minOf(array.length(), 10)).map { i -> array.getJSONObject(i).let {
                Entry(it.getString("id"), it.getString("name"), it.getString("url"),
                    it.optString("epg"), it.optBoolean("enabled", true))
            } }
        }.getOrDefault(emptyList())
    }
    fun enabled(module: String) = list(module).filter { it.enabled }
    fun publicList(module: String): JSONArray = JSONArray().apply { list(module).forEach { put(json(it)) } }
    fun save(module: String, input: JSONObject): String = synchronized(mutationLock) {
        val current = list(module)
        val supplied = input.optString("id")
        val existing = current.firstOrNull { it.id == supplied }
        require(supplied.isEmpty() || existing != null)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val name = input.getString("name").trim()
        require(name.length in 1..32 && name.none { it.code < 32 })
        val url = if (module == "sources") sourceAddress(input.getString("url")) else checkedAddress(input.getString("url"))
        val epg = input.optString("epg").takeIf { it.isNotBlank() }?.let(::checkedAddress).orEmpty()
        require(current.none { it.id != id && it.url == url })
        if (module == "sources") require(url != Preferences(context).tvBoxWarehouseUrl)
        require(existing != null || current.size < 10)
        if (module == "nas" && input.has("password")) {
            require(HerrinPersonalCredentialStore(context, "config-$id")
                .save(input.optString("username"), input.getString("password")))
        }
        val entry = Entry(id, name, url, epg, input.optBoolean("enabled", true))
        val result = current.filterNot { it.id == id } + entry
        check(prefs.edit().putString("services_$module", JSONArray().apply { result.forEach { put(json(it)) } }.toString()).commit())
        return id
    }
    fun remove(module: String, id: String) = synchronized(mutationLock) {
        val current = list(module)
        require(current.any { it.id == id })
        check(prefs.edit().putString("services_$module", JSONArray().apply { current.filterNot { it.id == id }.forEach { put(json(it)) } }.toString()).commit())
        if (module == "nas") HerrinPersonalCredentialStore(context, "config-$id").clear()
    }
    fun batchSources(raw: String) = synchronized(mutationLock) {
        val addresses = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map(::sourceAddress).distinct().toList()
        require(addresses.isNotEmpty())
        val current = list("sources")
        val primary = Preferences(context).tvBoxWarehouseUrl
        val added = addresses.filter { url -> url != primary && current.none { it.url == url } }
            .mapIndexed { index, url -> Entry(UUID.randomUUID().toString(), "影视源 ${current.size + index + 1}", url, "", true) }
        require(current.size + added.size <= 10)
        check(prefs.edit().putString("services_sources", JSONArray().apply { (current + added).forEach { put(json(it)) } }.toString()).commit())
    }
    fun nasAccounts(): List<HerrinWebDavAccount> = enabled("nas").map { entry ->
        val login = HerrinPersonalCredentialStore(context, "config-${entry.id}").load()
        HerrinWebDavAccount("NAS-${entry.id}", entry.url, login.first, login.second)
    }
    private fun json(e: Entry) = JSONObject().put("id", e.id).put("name", e.name).put("url", e.url)
        .put("epg", e.epg).put("enabled", e.enabled)
    companion object {
        private val mutationLock = Any()
        fun sourceAddress(raw: String): String {
            require(raw.length in 1..2048)
            return requireNotNull(TvBoxUrl.normalize(raw, allowHttp = true))
        }
        fun checkedAddress(raw: String): String {
            require(raw.length in 1..2048)
            val uri = URI(raw.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null)
            return uri.toASCIIString()
        }
    }
}
