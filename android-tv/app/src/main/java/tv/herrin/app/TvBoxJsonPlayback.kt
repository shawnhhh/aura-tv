package tv.herrin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * TVBox/FongMi type=1 JSON parse route. The user-applied warehouse provides
 * the exact parser endpoint. No browser-based sniffing, JavaScript execution, DRM
 * bypass, or open redirect-based URL discovery is performed.
 */
internal object TvBoxJsonPlayback {
    private val directExtensions = listOf(".m3u8", ".mpd", ".mp4", ".m4v",
        ".mkv", ".mov", ".webm", ".ts", ".flv", ".mp3", ".aac")

    fun choose(url: String, flag: String, parsers: List<TvBoxJsonParse>): TvBoxJsonParse? {
        if (TvBoxUrl.normalize(url, true) == null) return null
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (directExtensions.any { path.endsWith(it) }) return null
        // Explicit flag matches permit extensionless media pages. Otherwise,
        // only obvious HTML watch pages enter a configured JSON resolver.
        val selected = parsers.firstOrNull { it.flags.any { f -> f.equals(flag, true) } }
        if (selected != null) return selected
        return if (path.endsWith(".html") || path.endsWith(".htm"))
            parsers.firstOrNull { it.flags.isEmpty() } else null
    }

    fun parserUrl(parser: TvBoxJsonParse, sourceUrl: String, allowHttp: Boolean): String? {
        if (TvBoxUrl.normalize(sourceUrl, allowHttp) == null ||
            TvBoxUrl.normalize(parser.prefix, allowHttp) == null) return null
        // FongMi ParseJob.jsonParse uses parse.url + webUrl verbatim.
        // Encoding the entire nested URL changes the API contract for
        // popular TVBox JSON parsers, so follow upstream concatenation.
        // The source and parser prefix were separately normalized above.
        val address = parser.prefix + sourceUrl
        return TvBoxUrl.normalize(address, allowHttp)
    }

    fun decode(json: String, parser: TvBoxJsonParse, allowHttp: Boolean): PlaybackResult {
        val root = try { JSONObject(json) }
            catch (_: Exception) { return PlaybackResult.Unavailable("JSON 解析接口未返回有效 JSON") }
        val data = root.optJSONObject("data")
        val raw = root.optString("url").takeIf { it.isNotBlank() }
            ?: data?.optString("url").orEmpty()
        val url = TvBoxUrl.normalize(raw, allowHttp)
            ?: return PlaybackResult.Unavailable("JSON 解析接口未提供可用 HTTP(S) 媒体地址")
        val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
        if (path.endsWith(".html") || path.endsWith(".htm"))
            return PlaybackResult.Unavailable("JSON 解析接口仍返回网页，未得到视频地址")
        // Upstream ParseJob accepts headers either as an object or as
        // top-level User-Agent/Referer/ua fields in the returned JSON.
        val objectHeaders = TvBoxHeaderCodec.decode(
            root.optJSONObject("header") ?: data?.optJSONObject("header"))
        val directHeaders = buildMap<String, String> {
            for (key in listOf("User-Agent", "Referer", "ua")) {
                val value = root.optString(key).trim().ifBlank { data?.optString(key).orEmpty().trim() }
                if (value.length in 1..2_048 && '\\r' !in value && '\\n' !in value)
                    put(if (key == "ua") "User-Agent" else key, value)
            }
        }
        // A parser-provided Cookie/Authorization can only be used on the
        // parser's own origin. Never send saved site credentials to CDN hosts.
        val parserOrigin = runCatching { URI(parser.prefix) }.getOrNull()
        val mediaOrigin = runCatching { URI(url) }.getOrNull()
        val sameOrigin = parserOrigin != null && mediaOrigin != null &&
            parserOrigin.scheme.equals(mediaOrigin.scheme, ignoreCase = true) &&
            parserOrigin.host.equals(mediaOrigin.host, ignoreCase = true) &&
            (if (parserOrigin.port < 0) defaultPort(parserOrigin.scheme) else parserOrigin.port) ==
            (if (mediaOrigin.port < 0) defaultPort(mediaOrigin.scheme) else mediaOrigin.port)
        val headers = (objectHeaders + directHeaders).ifEmpty { parser.headers }
            .filterKeys { key ->
                key.equals("User-Agent", true) || key.equals("Referer", true) ||
                    (sameOrigin && (key.equals("Cookie", true) ||
                        key.equals("Authorization", true)))
            }
        val format = root.optString("format").ifBlank { data?.optString("format").orEmpty() }
        return PlaybackResult.Ready(PlaybackMedia(
            uri = url, headers = headers, allowHttpRedirects = allowHttp,
            declaredMime = PlaybackFormatRetry.declaredMime(format)
        ))
    }

    private fun defaultPort(scheme: String?): Int =
        if (scheme.equals("https", ignoreCase = true)) 443 else 80

    suspend fun resolve(
        parser: TvBoxJsonParse, sourceUrl: String, allowHttp: Boolean
    ): PlaybackResult = withContext(Dispatchers.IO) {
        val address = parserUrl(parser, sourceUrl, allowHttp)
            ?: return@withContext PlaybackResult.Unavailable("JSON 解析地址无效")
        val connection = try { URL(address).openConnection() as HttpURLConnection }
            catch (_: Exception) { return@withContext PlaybackResult.Unavailable("无法连接 JSON 解析接口") }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json, text/plain")
            for ((k, v) in parser.headers) if (k.equals("User-Agent", true) || k.equals("Referer", true))
                connection.setRequestProperty(k, v)
            if (connection.responseCode != 200)
                return@withContext PlaybackResult.Unavailable("JSON 解析接口 HTTP 响应异常")
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.startsWith("text/html") || contentType.startsWith("video/") ||
                contentType.startsWith("image/"))
                return@withContext PlaybackResult.Unavailable("JSON 解析接口返回的不是 JSON")
            if (connection.contentLengthLong > 65_536)
                return@withContext PlaybackResult.Unavailable("JSON 解析响应超过大小限制")
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (body.size() + n > 65_536)
                        return@withContext PlaybackResult.Unavailable("JSON 解析响应超过大小限制")
                    body.write(buffer, 0, n)
                }
            }
            decode(body.toString(Charsets.UTF_8.name()), parser, allowHttp)
        } catch (_: Exception) {
            PlaybackResult.Unavailable("JSON 解析接口连接失败或超时")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Player-only coroutine; memoized on the exact film/source/episode and retry
 * epoch. Never starts media playback before the configured JSON API resolves.
 */
@Composable
internal fun CmsJsonPlayback(
    sourceUrl: String, parser: TvBoxJsonParse, allowHttp: Boolean,
    retryEpoch: Int
): PlaybackResult? {
    var result by remember(sourceUrl, parser, allowHttp, retryEpoch) {
        mutableStateOf<PlaybackResult?>(null)
    }
    LaunchedEffect(sourceUrl, parser, allowHttp, retryEpoch) {
        result = TvBoxJsonPlayback.resolve(parser, sourceUrl, allowHttp)
    }
    return result
}
