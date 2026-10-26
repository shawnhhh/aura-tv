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

    companion object {
        const val MAX_PROFILES = 10
        private const val KEY_PROFILES = "catalog_profiles_v1"
        private const val KEY_ACTIVE = "active_catalog_profile"
    }
}
