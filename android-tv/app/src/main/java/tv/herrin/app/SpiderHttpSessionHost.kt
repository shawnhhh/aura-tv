package tv.herrin.app

import com.github.catvod.Runtime as CatVodRuntime

import com.fongmi.quickjs.host.Host
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ephemeral per-Spider-session cookie storage for CatVod Net's Host callbacks.
 *
 * Replaces Host.NONE for network calls that explicitly enable cookies. OkHttp's
 * Cookie parser enforces host/domain, path, expiry and Secure matching.
 *
 * Deliberately not browser/JS auth: no Activity, login UI, persistent browser
 * profile or cross-site shared cookie jar is created in the :spider process.
 * Values are never logged, saved on disk or reported back to the UI.
 */
internal class SpiderHttpSessionHost : Host {
    private val cookies = LinkedHashMap<String, Cookie>()

    override fun dialog(options: String): java.util.concurrent.CompletableFuture<String> =
        CatVodRuntime.dialog(options, null)

    override fun dialog(options: String, onAction: java.util.function.Function<String, java.util.concurrent.CompletableFuture<String>>): java.util.concurrent.CompletableFuture<String> =
        CatVodRuntime.dialog(options, onAction)

    @Synchronized
    override fun getCookie(url: String?): String {
        val target = url?.toHttpUrlOrNull() ?: return ""
        evictExpired()
        return cookies.values.asSequence()
            .filter { it.matches(target) }
            .sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    override fun setCookie(url: String?, cookie: String?): Boolean {
        if (cookie == null || cookie.length !in 1..4096) return false
        val target = url?.toHttpUrlOrNull() ?: return false
        val parsed = Cookie.parse(target, cookie) ?: return false
        evictExpired()
        val key = key(parsed)
        cookies.remove(key)
        if (parsed.expiresAt > System.currentTimeMillis()) {
            if (cookies.size >= MAX_COOKIES) cookies.remove(cookies.keys.first())
            cookies[key] = parsed
        }
        return true
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    // CatVod's network callback uses the cookie methods. Returning an OS agent
    // is only a hint to Spider code asking Host directly, not a browser.
    override fun getUserAgent(): String =
        System.getProperty("http.agent")?.take(256).orEmpty()

    private fun key(cookie: Cookie) = "${cookie.domain}\u0000${cookie.path}\u0000${cookie.name}"

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt <= now }
    }

    private companion object {
        const val MAX_COOKIES = 128
    }
}
