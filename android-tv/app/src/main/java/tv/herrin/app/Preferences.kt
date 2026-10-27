package tv.herrin.app

import android.content.Context
import java.security.MessageDigest

class Preferences(context: Context) : FavoritesStore {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    // Retain the old offline-demo key and its contents; all remote favorites are
    // scoped to the canonical endpoint to avoid collisions between source IDs.
    private fun favoriteKey(scope: String): String {
        if (scope == "herrin-offline-demo") return "favorites"
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "favorites-$digest"
    }
    fun idsFor(scope: String): Set<String> = prefs.getStringSet(favoriteKey(scope), emptySet())!!.toSet()
    fun toggleFor(scope: String, id: String) {
        val next = idsFor(scope).toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet(favoriteKey(scope), next).apply()
    }
    /** One-time best-effort copy of legacy global favorites into a migrated remote catalog. */
    fun preserveLegacyFavoritesFor(scope: String) {
        val key = favoriteKey(scope)
        if (!prefs.contains(key)) prefs.edit().putStringSet(key, ids()).commit()
    }
    override fun ids(): Set<String> = idsFor("herrin-offline-demo")
    override fun toggle(id: String) = toggleFor("herrin-offline-demo", id)
    /** Optional, user-entered licensed HTTPS catalog endpoint and playable test-media URL. */
    var catalogUrl: String
        get() = prefs.getString("catalog_url", "") ?: ""
        set(value) { prefs.edit().putString("catalog_url", value).apply() }
    var testMediaUrl: String
        get() = prefs.getString("test_media_url", "") ?: ""
        set(value) { prefs.edit().putString("test_media_url", value).apply() }
    /** Explicit user-supplied HTTPS IPTV M3U; never discovered or bundled. */
    var livePlaylistUrl: String
        get() = prefs.getString("live_playlist_url", "") ?: ""
        set(value) { prefs.edit().putString("live_playlist_url", value).apply() }
    var liveEpgUrl: String
        get() = prefs.getString("live_epg_url", "") ?: ""
        set(value) { prefs.edit().putString("live_epg_url", value).apply() }
    /** User-applied URL. Only a matching, explicitly applied Spider JAR may auto-run again. */
    var tvBoxWarehouseUrl: String
        get() = prefs.getString("tvbox_warehouse_url", "") ?: ""
        set(value) { prefs.edit().putString("tvbox_warehouse_url", value).apply() }
    /** Trust is scoped to the exact source URL and exact URL+MD5 JAR spec. */
    var tvBoxTrustedSpiderRootUrl: String
        get() = prefs.getString("tvbox_trusted_spider_root", "") ?: ""
        set(value) { prefs.edit().putString("tvbox_trusted_spider_root", value).apply() }
    var tvBoxTrustedSpiderJarSpec: String
        get() = prefs.getString("tvbox_trusted_spider_jar", "") ?: ""
        set(value) { prefs.edit().putString("tvbox_trusted_spider_jar", value).apply() }
    /** Last user-submitted live playlist URL; actual validated channels live in TvBoxLiveStore. */
    var tvBoxLiveSourceUrl: String
        get() = prefs.getString("tvbox_live_source_url", "") ?: ""
        set(value) { prefs.edit().putString("tvbox_live_source_url", value).apply() }
    /** Set only after a manual import: show already-approved offline channels on launch. */
    var tvBoxLiveActive: Boolean
        get() = prefs.getBoolean("tvbox_live_active", false)
        set(value) { prefs.edit().putBoolean("tvbox_live_active", value).apply() }
    var reducedMotion: Boolean
        get() = prefs.getBoolean("reduced_motion", false)
        set(value) { prefs.edit().putBoolean("reduced_motion", value).apply() }
}
