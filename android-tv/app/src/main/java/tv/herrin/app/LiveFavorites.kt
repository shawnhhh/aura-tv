package tv.herrin.app

import android.content.Context
import java.security.MessageDigest

/** Private per-playlist favorites. Persist hashes, not stream URLs or their tokens. */
internal class LiveFavorites(context: Context) {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun key(playlist: String) = "live-favorites-${sha(playlist)}"
    private fun token(channelUrl: String) = sha(channelUrl)

    fun contains(playlist: String, channelUrl: String): Boolean =
        token(channelUrl) in prefs.getStringSet(key(playlist), emptySet()).orEmpty()

    fun toggle(playlist: String, channelUrl: String): Boolean {
        require(AuthorizedUrls.https(playlist) != null)
        require(AuthorizedUrls.https(channelUrl) != null)
        val ids = prefs.getStringSet(key(playlist), emptySet()).orEmpty().toMutableSet()
        val added = ids.add(token(channelUrl))
        if (!added) ids.remove(token(channelUrl))
        check(prefs.edit().putStringSet(key(playlist), ids).commit())
        return added
    }
}
