package tv.herrin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Server credentials are isolated by UUID and encrypted together with server identity. */
internal class PersonalConnections(private val context: Context) {
    private val prefs = context.getSharedPreferences("herrin", 0)
    data class Connection(val id: String, val name: String, val protocol: String, val host: String,
        val port: Int, val username: String, val password: String, val domain: String,
        val encryption: Boolean, val enabled: Boolean, val rootPath: String = "") {
        val endpoint get() = PersonalStoragePolicy.endpoint(protocol, host, port)
        fun address(path: String) = URI(protocol, null, PersonalStoragePolicy.host(host), port, "/" + PersonalStoragePolicy.path(path), null, null).toASCIIString()
        override fun toString() = "Personal storage ($id)"
        fun publicJson() = JSONObject().put("id", id).put("name", name).put("protocol", protocol)
            .put("host", host).put("port", port).put("username", username).put("domain", domain)
            .put("encryption", encryption).put("enabled", enabled).put("path", rootPath)
    }
    private fun ids() = JSONArray(prefs.getString("personal_connection_ids", "[]")).let { a -> (0 until a.length()).map(a::getString) }
    fun list(): List<Connection> = synchronized(lock) { ids().mapNotNull(::get) }
    fun get(id: String): Connection? {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return HerrinPersonalCredentialStore(context, "config-$id").loadPayload()?.let { raw ->
            val j = JSONObject(raw)
            Connection(id, j.getString("name"), j.getString("protocol"), j.getString("host"), j.getInt("port"),
                j.optString("username"), j.optString("password"), j.optString("domain"), j.optBoolean("encryption"), j.optBoolean("enabled", true), j.optString("path"))
        }
    }
    fun save(input: JSONObject): Connection = synchronized(lock) {
        val supplied = input.optString("id")
        val old = supplied.takeIf(String::isNotEmpty)?.let { requireNotNull(get(it)) }
        val id = old?.id ?: UUID.randomUUID().toString()
        require(old != null || ids().size < 32)
        val protocol = input.getString("protocol")
        val host = PersonalStoragePolicy.host(input.getString("host"))
        val port = input.optInt("port", if (protocol == "https") 443 else if (protocol == "http") 80 else 445)
        PersonalStoragePolicy.endpoint(protocol, host, port)
        val name = input.getString("name").trim()
        require(name.length in 1..64 && name.none { it.code < 32 })
        // Retain blank secrets only for the exact same authority and account.
        val user = input.optString("username")
        val domain = input.optString("domain")
        val same = old?.takeIf { it.protocol == protocol && it.host.equals(host, true) && it.port == port && it.username == user && it.domain == domain }
        val password = input.optString("password").ifEmpty { same?.password.orEmpty() }
        require(user.length <= 256 && password.length <= 2048 && domain.length <= 256)
        val value = Connection(id, name, protocol, host, port, user, password, domain, input.optBoolean("encryption"), input.optBoolean("enabled", old?.enabled ?: true), PersonalStoragePolicy.path(if(input.has("path")) input.getString("path") else old?.rootPath.orEmpty()))
        check(HerrinPersonalCredentialStore(context, "config-$id").savePayload(value.publicJson().put("password", password).toString()))
        check(prefs.edit().putString("personal_connection_ids", JSONArray((ids() + id).distinct()).toString()).commit())
        value
    }
    fun status(id: String, status: String) {
        require(status in setOf("connected", "failed", "unknown"))
        check(prefs.edit().putString("personal_status_$id", status).commit())
    }
    fun migrationWarning() = prefs.getBoolean("personal_migration_attention", false)
    fun publicList() = JSONArray().apply { list().forEach { c ->
        put(c.publicJson().put("status", prefs.getString("personal_status_${c.id}", "unknown")))
    } }
    fun delete(id: String) = synchronized(lock) {
        require(ids().contains(id))
        check(prefs.edit().putString("personal_connection_ids", JSONArray(ids().filterNot { it == id }).toString()).commit())
        HerrinPersonalCredentialStore(context, "config-$id").clear()
        prefs.edit().remove("personal_status_$id").commit()
    }
    /** Idempotent migration: originals stay intact until explicit user edits/removal. */
    fun migrate(index: PersonalMediaIndex) = synchronized(lock) {
        if (prefs.getBoolean("personal_migrated_v1", false)) return@synchronized
        fun add(key: String, name: String, url: String, user: String, password: String, domain: String = "", encryption: Boolean = false, enabled: Boolean = true) {
            if (url.isBlank() || prefs.contains("personal_migration_$key")) return
            runCatching {
            val u = URI(url); val protocol = u.scheme.lowercase(java.util.Locale.ROOT)
            val cleanPath = PersonalStoragePolicy.path(u.path.orEmpty())
            require(!u.host.isNullOrBlank())
            PersonalStoragePolicy.endpoint(protocol, u.host, if(u.port > 0) u.port else if(protocol == "https") 443 else if(protocol == "http") 80 else 445)
            val c = save(JSONObject().put("name", name).put("protocol", protocol).put("host", u.host)
                .put("port", if (u.port > 0) u.port else if (protocol == "https") 443 else if (protocol == "http") 80 else 445)
                .put("username", user).put("password", password).put("domain", domain).put("encryption", encryption).put("enabled", enabled).put("path", cleanPath))
            index.folder(c.id, cleanPath, "auto")
            check(prefs.edit().putString("personal_migration_$key", c.id).commit())
            }.onFailure { check(prefs.edit().putBoolean("personal_migration_attention", true).commit()) }

        }
        val legacy = Preferences(context)
        val dav = HerrinPersonalCredentialStore(context, "nas").load()
        add("nas", "WebDAV", legacy.nasWebDavUrl, dav.first, dav.second)
        HerrinSmbConfigStore(context).account()?.let { add("smb", "SMB", it.url, it.username, it.password, it.domain, it.encryption) }
        AdditionalServiceConfigs(context).list("nas").forEach { e ->
            val login = HerrinPersonalCredentialStore(context, "config-${e.id}").load()
            add(e.id, e.name, e.url, login.first, login.second, enabled = e.enabled)
        }
        check(prefs.edit().putBoolean("personal_migrated_v1", true).commit())
    }
    private companion object { val lock = Any() }
}
