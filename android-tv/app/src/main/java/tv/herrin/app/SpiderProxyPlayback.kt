package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * FongMi accepts proxy://do=jar&... as a CatVod internal route.
     * Convert it ONLY into the active selected Spider's authenticated
     * loopback endpoint before invoking the HTTP-only playback router.
     */
    internal fun rebaseUrl(raw: String, siteKey: String, proxy: SpiderLoopbackProxy): String? {
        if (siteKey.isBlank() || !proxy.isOpen || raw.length !in 1..MAX_URL) return null
        val legacy = raw.startsWith("proxy://", ignoreCase = true)
        val parameters = if (legacy) {
            val tail = raw.substring(8).removePrefix("?")
            if (tail.contains('/') || tail.contains('#')) return null
            if (tail.isEmpty()) emptyMap() else query(tail)
        } else {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (!local(uri) || uri.path != "/proxy") return raw
            query(uri.rawQuery)
        }
        if (parameters.isEmpty() && !(legacy && raw.substring(8).trim('?').isEmpty()))
            return null
        if (parameters["do"].let { it != null && it != "jar" }) return null
        if (parameters["siteKey"].let { it != null && it != siteKey }) return null
        if (parameters.size !in 0..24) return null
        val extra = parameters.entries.filterNot { it.key in setOf("session", "do", "siteKey") }
            .joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
        return proxy.url + if (extra.isEmpty()) "" else "&$extra"
    }

    /**
     * Normalize Spider proxy aliases while retaining all other parser fields
     * and selected quality options. No network request occurs here.
     */
    fun normalizePlayerResponse(
        rawJson: String, siteKey: String, proxy: SpiderLoopbackProxy
    ): String? {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        fun translate(url: String): String? {
            val legacy = url.startsWith("proxy://", ignoreCase = true)
            val localProxy = runCatching {
                val uri = URI(url)
                local(uri) && uri.path == "/proxy"
            }.getOrDefault(false)
            return if (legacy || localProxy) rebaseUrl(url, siteKey, proxy) else url
        }
        val url = obj.opt("url")
        when (url) {
            is String -> obj.put("url", translate(url) ?: return null)
            is JSONArray -> {
                for (i in 0 until minOf(32, url.length())) {
                    val value = url.optString(i)
                    // Quality labels in alternating label/url arrays remain intact.
                    url.put(i, translate(value) ?: return null)
                }
            }
            is JSONObject -> {
                val values = url.optJSONArray("values")
                if (values != null) {
                    for (i in 0 until minOf(values.length(), 20)) {
                        val entry = values.optJSONObject(i) ?: continue
                        if (entry.has("v")) {
                            entry.put("v", translate(entry.optString("v")) ?: return null)
                        }
                    }
                }
            }
        }
        return obj.toString()
    }

    fun rebind(media: PlaybackMedia, siteKey: String, proxy: SpiderLoopbackProxy): PlaybackResult {
        if (siteKey.isBlank() || !proxy.isOpen)
            return PlaybackResult.Unavailable("本机 Spider 播放代理未启动（PROXY_NOT_RUNNING）")
        val primary = rebaseUrl(media.uri, siteKey, proxy)
            ?: return PlaybackResult.Unavailable(
                "此线路的代理不属于当前 Spider 站点（PROXY_INVALID_ROUTE）")
        val alternates = media.alternateUris.mapNotNull {
            rebaseUrl(it, siteKey, proxy)
        }.filter { it != primary }.distinct()
        return PlaybackResult.Ready(media.copy(uri = primary, alternateUris = alternates))
    }

    /** Bounded health probe, never reveals URL or private session. */
    enum class Health {
        READY, NOT_CATVOD, INVALID_SESSION, INVALID_PORT, HTTP_403, HTTP_404,
        HTTP_5XX, HTTP_OTHER, CONNECT_REFUSED, TIMEOUT, IO_FAILURE
    }

    suspend fun diagnose(raw: String): Health = withContext(Dispatchers.IO) {
        if (!isCatVodProxy(raw)) return@withContext Health.NOT_CATVOD
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return@withContext Health.NOT_CATVOD
        if (uri.port !in 1..65535) return@withContext Health.INVALID_PORT
        val params = query(uri.rawQuery)
        val session = params["session"]?.takeIf { it.length in 24..64 }
            ?: return@withContext Health.INVALID_SESSION
        val site = params["siteKey"]?.takeIf { it.length in 1..128 }
            ?: return@withContext Health.INVALID_SESSION
        val url = "http://127.0.0.1:${uri.port}/health?session=" +
            URLEncoder.encode(session, "UTF-8") + "&siteKey=" +
            URLEncoder.encode(site, "UTF-8")
        val conn = try { URL(url).openConnection() as HttpURLConnection }
            catch (_: Exception) { return@withContext Health.IO_FAILURE }
        try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.instanceFollowRedirects = false
            when (conn.responseCode) {
                204 -> Health.READY
                403 -> Health.HTTP_403
                404 -> Health.HTTP_404
                in 500..599 -> Health.HTTP_5XX
                else -> Health.HTTP_OTHER
            }
        } catch (_: java.net.ConnectException) { Health.CONNECT_REFUSED }
        catch (_: java.net.SocketTimeoutException) { Health.TIMEOUT }
        catch (_: Exception) { Health.IO_FAILURE }
        finally { conn.disconnect() }
    }

    suspend fun ping(raw: String): Boolean = diagnose(raw) == Health.READY
}
