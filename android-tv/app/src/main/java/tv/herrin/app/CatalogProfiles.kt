package tv.herrin.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved user-authorized catalog URL. No spider code, tokens or inferred playback links. */
data class CatalogProfile(val id: String, val name: String, val url: String)

sealed interface SaveProfileResult {
    data class Saved(val profile: CatalogProfile, val existing: Boolean) : SaveProfileResult
    data class Error(val reason: String) : SaveProfileResult
}

sealed interface ImportProfilesResult {
    data class Imported(val added: Int, val skipped: Int) : ImportProfilesResult
    data class Error(val reason: String) : ImportProfilesResult
}

/** Pure versioned format, independently testable without Android storage. */
internal object CatalogProfileCodec {
    private const val LIMIT = 10
    fun encode(profiles: List<CatalogProfile>): String {
        require(profiles.size <= LIMIT)
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("name", profile.name).put("url", profile.url))
            }
        }.toString()
    }

    fun decode(raw: String): List<CatalogProfile> {
        val array = JSONArray(raw)
        require(array.length() <= LIMIT)
        val ids = HashSet<String>()
        val urls = HashSet<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            val url = item.getString("url")
            require(id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            require(name.isNotBlank() && name.length <= 32 && name.none { it.code < 32 || it.code == 127 })
            require(AuthorizedUrls.https(url) == url)
            require(ids.add(id) && urls.add(url))
            CatalogProfile(id, name, url)
        }
    }
}

/** Bounded local profiles, with one-way migration of the previously configured catalog_url. */
class CatalogProfiles(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_PROFILES)) {
            val legacy = AuthorizedUrls.https(prefs.getString("catalog_url", "") ?: "")
            val list = if (legacy == null) emptyList() else listOf(CatalogProfile("legacy", "原有目录", legacy))
            val editor = prefs.edit().putString(KEY_PROFILES, CatalogProfileCodec.encode(list))
            if (legacy == null) editor.remove(KEY_ACTIVE)
            else editor.putString(KEY_ACTIVE, "legacy").putString("catalog_url", legacy)
            editor.commit()
            // The old app used one favorites set even when a remote catalog was
            // active. Preserve those IDs for that catalog without deleting the
            // original offline set, since their provenance cannot be recovered.
            if (legacy != null) Preferences(context).preserveLegacyFavoritesFor(legacy)
        }
    }

    fun list(): List<CatalogProfile> = runCatching {
        CatalogProfileCodec.decode(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)
        ?.takeIf { id -> list().any { it.id == id } }

    fun activate(id: String?): Boolean {
        val found = if (id == null) null else (list().firstOrNull { it.id == id } ?: return false)
        val edit = prefs.edit().putString("catalog_url", found?.url ?: "")
        if (found == null) edit.remove(KEY_ACTIVE) else edit.putString(KEY_ACTIVE, found.id)
        return edit.commit()
    }

    fun addAndActivate(rawName: String, rawUrl: String): SaveProfileResult {
        val url = AuthorizedUrls.https(rawUrl)
            ?: return SaveProfileResult.Error("目录地址必须为合法 HTTPS 链接")
        val current = list()
        current.firstOrNull { it.url == url }?.let { existing ->
            return if (activate(existing.id)) SaveProfileResult.Saved(existing, true)
            else SaveProfileResult.Error("保存失败，请重试")
        }
        if (current.size >= MAX_PROFILES) return SaveProfileResult.Error("最多保存 10 个目录")
        val name = rawName.trim().ifBlank { "我的目录 ${current.size + 1}" }
        if (name.length > 32 || name.any { it.code < 32 || it.code == 127 })
            return SaveProfileResult.Error("目录名称不能超过 32 字且不能包含控制字符")
        val profile = CatalogProfile(UUID.randomUUID().toString(), name, url)
        val saved = prefs.edit()
            .putString(KEY_PROFILES, CatalogProfileCodec.encode(current + profile))
            .putString(KEY_ACTIVE, profile.id)
            .putString("catalog_url", url)
            .commit()
        return if (saved) SaveProfileResult.Saved(profile, false)
        else SaveProfileResult.Error("保存失败，请重试")
    }

    /** Edits an existing source atomically; changing URL naturally changes favorites/history scope. */
    fun update(id: String, rawName: String, rawUrl: String): SaveProfileResult {
        val current = list()
        val original = current.firstOrNull { it.id == id }
            ?: return SaveProfileResult.Error("目录不存在")
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 32 || name.any { it.code < 32 || it.code == 127 })
            return SaveProfileResult.Error("目录名称应为 1–32 字，不能包含控制字符")
        val url = AuthorizedUrls.https(rawUrl)
            ?: return SaveProfileResult.Error("目录地址必须为合法 HTTPS 链接")
        if (current.any { it.id != id && it.url == url })
            return SaveProfileResult.Error("另一个目录已使用这个地址")
        val updated = original.copy(name = name, url = url)
        val editor = prefs.edit().putString(
            KEY_PROFILES, CatalogProfileCodec.encode(current.map { if (it.id == id) updated else it })
        )
        if (activeId() == id) editor.putString("catalog_url", url)
        return if (editor.commit()) SaveProfileResult.Saved(updated, true)
        else SaveProfileResult.Error("保存失败，请重试")
    }

    /** Deleting the active source falls back to offline demo; unrelated user data is untouched. */
    fun delete(id: String): Boolean {
        val current = list()
        if (current.none { it.id == id }) return false
        val editor = prefs.edit().putString(
            KEY_PROFILES, CatalogProfileCodec.encode(current.filterNot { it.id == id })
        )
        if (activeId() == id) editor.remove(KEY_ACTIVE).putString("catalog_url", "")
        return editor.commit()
    }

    /** Import/export use only local JSON; a rejected batch never partially mutates saved sources. */
    fun exportJson(): String = CatalogProfileCodec.encode(list())

    fun importJson(raw: String): ImportProfilesResult {
        val imported = runCatching { CatalogProfileCodec.decode(raw) }
            .getOrElse { return ImportProfilesResult.Error("导入失败：请提供合法的 HERRIN 目录 JSON") }
        val current = list()
        val currentUrls = current.mapTo(HashSet()) { it.url }
        val additions = imported.filter { currentUrls.add(it.url) }
        if (current.size + additions.size > MAX_PROFILES)
            return ImportProfilesResult.Error("导入超出最多 10 个目录的限制，尚未保存任何更改")
        val allIds = current.mapTo(HashSet()) { it.id }
        val safeAdditions = additions.map { profile ->
            var id: String
            do { id = UUID.randomUUID().toString() } while (!allIds.add(id))
            profile.copy(id = id)
        }
        if (safeAdditions.isEmpty()) return ImportProfilesResult.Imported(0, imported.size)
        val saved = prefs.edit().putString(
            KEY_PROFILES, CatalogProfileCodec.encode(current + safeAdditions)
        ).commit()
        return if (saved) ImportProfilesResult.Imported(safeAdditions.size, imported.size - additions.size)
        else ImportProfilesResult.Error("导入失败，请重试")
    }

    companion object {
        const val MAX_PROFILES = 10
        private const val KEY_PROFILES = "catalog_profiles_v1"
        private const val KEY_ACTIVE = "active_catalog_profile"
    }
}
