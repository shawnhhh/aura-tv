package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The source may return a CatVod proxy URL containing TVBox's fixed port, or
 * an older HERRIN loopback port from a previous session. Neither listener
 * exists in this worker. Only rebase known CatVod /proxy?do=jar requests for
 * the CURRENT selected site, never arbitrary localhost URLs.
 *
 * The health endpoint verifies a real listener across :spider and the UI
 * process before handing an opaque URL to Media3.
 */
internal object SpiderProxyPlayback {
    private const val MAX_URL = 4_096
    private fun local(uri: URI): Boolean =
        uri.scheme == "http" && uri.host?.lowercase() in
            setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun isCatVodProxy(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        local(uri) && uri.path == "/proxy" && query(uri.rawQuery)["do"] == "jar"
    }.getOrDefault(false)

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty() || raw.length > MAX_URL) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (part in raw.split('&').take(32)) {
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            if (key.isBlank() || key in result) return emptyMap()
            result[key] = value
        }
        return result
    }

    fun rebind(media: PlaybackMedia, siteKey: String, proxy: SpiderLoopbackProxy): PlaybackResult {
        if (siteKey.isBlank() || !proxy.isOpen)
            return PlaybackResult.Unavailable("本机 Spider 播放代理未启动")
        fun fix(raw: String): String? {
            if (raw.length !in 1..MAX_URL) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (!local(uri) || uri.path != "/proxy") return raw
            val map = query(uri.rawQuery)
            if (map["do"] != "jar" || map["siteKey"].orEmpty().let { it.isNotEmpty() && it != siteKey })
                return null
            if (map.size !in 1..24) return null
            val extra = map.entries.filterNot { it.key in setOf("session", "do", "siteKey") }
                .joinToString("&") { (k, v) ->
                    URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                }
            return proxy.url + if (extra.isEmpty()) "" else "&$extra"
        }
        val primary = fix(media.uri) ?: return PlaybackResult.Unavailable(
            "此线路的本地代理不属于当前 Spider 站点，无法安全转接")
        val alternates = media.alternateUris.mapNotNull(::fix).filter { it != primary }.distinct()
        return PlaybackResult.Ready(media.copy(uri = primary, alternateUris = alternates))
    }

    /** Internal /health is a no-media, per-session loopback endpoint. */
    suspend fun ping(raw: String): Boolean = withContext(Dispatchers.IO) {
        if (!isCatVodProxy(raw)) return@withContext false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return@withContext false
        val params = query(uri.rawQuery)
        val session = params["session"]?.takeIf { it.length in 24..64 }
            ?: return@withContext false
        val site = params["siteKey"]?.takeIf { it.length in 1..128 }
            ?: return@withContext false
        val url = "http://127.0.0.1:${uri.port}/health?session=" +
            URLEncoder.encode(session, "UTF-8") + "&siteKey=" +
            URLEncoder.encode(site, "UTF-8")
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrNull() ?: return@withContext false
        try {
            conn.connectTimeout = 1_500
            conn.readTimeout = 1_500
            conn.instanceFollowRedirects = false
            conn.responseCode == 204
        } catch (_: Exception) { false }
        finally { conn.disconnect() }
    }
}
