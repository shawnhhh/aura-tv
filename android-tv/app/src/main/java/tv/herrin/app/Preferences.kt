package tv.herrin.app

import android.content.Context

class Preferences(context: Context) : FavoritesStore {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    override fun ids(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()
    override fun toggle(id: String) {
        val next = ids().toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet("favorites", next).apply()
    }
    /** Optional, user-entered licensed HTTPS catalog endpoint and playable test-media URL. */
    var catalogUrl: String
        get() = prefs.getString("catalog_url", "") ?: ""
        set(value) { prefs.edit().putString("catalog_url", value).apply() }
    var testMediaUrl: String
        get() = prefs.getString("test_media_url", "") ?: ""
        set(value) { prefs.edit().putString("test_media_url", value).apply() }
    var reducedMotion: Boolean
        get() = prefs.getBoolean("reduced_motion", false)
        set(value) { prefs.edit().putBoolean("reduced_motion", value).apply() }
}
