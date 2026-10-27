package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL

/** TVBox metadata is untrusted: no Spider, JAR, JavaScript or site API is executed. */
internal data class TvBoxWarehouse(val name: String, val url: String)
internal data class TvBoxSite(val name: String, val needsAdapter: Boolean)
internal data class TvBoxLive(val name: String, val playlistUrl: String?)

internal sealed interface TvBoxConfig {
    data class Warehouses(val entries: List<TvBoxWarehouse>, val ignored: Int) : TvBoxConfig
    data class Single(val sites: List<TvBoxSite>, val lives: List<TvBoxLive>, val hasExternalEngine: Boolean) : TvBoxConfig
    data class HerrinCatalog(val address: String, val count: Int) : TvBoxConfig
    data class Error(val reason: String) : TvBoxConfig
}

internal object TvBoxUrl {
    /** Normalize internationalized domain names (e.g. 饭太硬.net) for HTTP(S). */
    fun normalize(raw: String, allowHttp: Boolean = false): String? {
        return try {
        val trimmed = raw.trim()
        if (trimmed.length > 2048 || trimmed.any { it.isWhitespace() || Character.isISOControl(it) || it == '\\' }) return null
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val uri = URI(trimmed)
        // An explicit authority is required. Prevent credential-bearing URLs and fragments.
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        val authority = uri.rawAuthority ?: return null
        val rawHost = authority.substringBeforeLast(':').takeIf { ':' in authority && !authority.startsWith('[') }
            ?: authority
        val host = if (uri.host != null) uri.host else rawHost.substringBefore(':')
        if (host.isBlank() || host.contains('@') || host.contains('%')) return null
        val asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        if (asciiHost.isBlank() || asciiHost.length > 253) return null
        val port = if (uri.port != -1) uri.port else {
            val suffix = authority.removePrefix(host)
            if (suffix.startsWith(':')) suffix.drop(1).toIntOrNull() ?: return null else -1
        }
        if (port != -1 && port !in 1..65535) return null
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        URI("$scheme://$asciiHost${if (port == -1) "" else ":$port"}$path$query").toASCIIString()
        } catch (_: Exception) {
            null
        }
    }
}

internal object TvBoxConfigParser {
    private const val MAX_ENTRIES = 100

    fun parse(json: String, origin: String): TvBoxConfig {
        val root = try { JSONObject(json.trimStart('\uFEFF', ' ', '\n', '\r', '\t')) }
        catch (_: JSONException) { return TvBoxConfig.Error("配置不是有效 JSON；可能返回了图片或网页") }

        if (root.has("urls")) {
            val urls = root.optJSONArray("urls") ?: return TvBoxConfig.Error("多仓 urls 必须是数组")
            val entries = mutableListOf<TvBoxWarehouse>()
            val seen = mutableSetOf<String>()
            var ignored = (urls.length() - MAX_ENTRIES).coerceAtLeast(0)
            for (i in 0 until minOf(urls.length(), MAX_ENTRIES)) {
                val entry = urls.optJSONObject(i)
                if (entry == null) { ignored++; continue }
                val url = TvBoxUrl.normalize(entry.optString("url"), allowHttp = true)
                if (url == null || !seen.add(url)) { ignored++; continue }
                val name = entry.optString("name").trim().take(48).ifBlank { "仓库 ${entries.size + 1}" }
                entries.add(TvBoxWarehouse(name, url))
            }
            return if (entries.isEmpty()) TvBoxConfig.Error("多仓中没有可识别的 HTTP(S) 单仓地址")
                else TvBoxConfig.Warehouses(entries, ignored)
        }
        if (root.has("sites") || root.has("lives") || root.has("spider")) {
            val siteJson = root.optJSONArray("sites") ?: JSONArray()
            val liveJson = root.optJSONArray("lives") ?: JSONArray()
            val sites = (0 until minOf(siteJson.length(), 300)).mapNotNull { i ->
                siteJson.optJSONObject(i)?.let { site ->
                    val title = site.optString("name").trim().take(64)
                    if (title.isBlank()) null else TvBoxSite(title, site.optString("api").isNotBlank())
                }
            }
            val lives = (0 until minOf(liveJson.length(), 100)).mapNotNull { i ->
                liveJson.optJSONObject(i)?.let { live ->
                    val title = live.optString("name").trim().take(64)
                    if (title.isBlank()) null else {
                        val address = live.optString("url")
                        val playlist = TvBoxUrl.normalize(address)?.takeIf {
                            URI(it).path.lowercase().endsWith(".m3u") || URI(it).path.lowercase().endsWith(".m3u8")
                        }
                        TvBoxLive(title, playlist)
                    }
                }
            }
            return TvBoxConfig.Single(sites, lives, root.optString("spider").isNotBlank() || sites.any { it.needsAdapter })
        }
        if (root.optInt("version", -1) == 1 && root.optJSONArray("items") != null) {
            return TvBoxConfig.HerrinCatalog(origin, root.getJSONArray("items").length())
        }
        return TvBoxConfig.Error("未识别的配置：需要 TVBox urls / sites / lives 或 HERRIN items")
    }
}

internal class TvBoxConfigRepository {
    suspend fun load(input: String, allowHttp: Boolean = false): TvBoxConfig = withContext(Dispatchers.IO) {
        val address = TvBoxUrl.normalize(input, allowHttp)
            ?: return@withContext TvBoxConfig.Error("只接受 HTTPS；如需读取 HTTP，请先手动开启本次授权")
        val connection = try { URL(address).openConnection() as HttpURLConnection }
        catch (_: Exception) { return@withContext TvBoxConfig.Error("无法建立配置连接") }
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in 300..399) return@withContext TvBoxConfig.Error("配置地址发生跳转；请检查最终 HTTPS 地址后重新输入")
            if (status !in 200..299) return@withContext TvBoxConfig.Error("配置读取失败：HTTP $status")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("text/html")) {
                return@withContext TvBoxConfig.Error("地址返回了图片或网页，不是 TVBox JSON 配置")
            }
            val maxBytes = 1024 * 1024
            if (connection.contentLengthLong > maxBytes) return@withContext TvBoxConfig.Error("配置超过 1 MB 安全限制")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { inputStream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (bytes.size() + read > maxBytes) return@withContext TvBoxConfig.Error("配置超过 1 MB 安全限制")
                    bytes.write(buffer, 0, read)
                }
            }
            TvBoxConfigParser.parse(bytes.toString(Charsets.UTF_8.name()), address)
        } catch (_: Exception) {
            TvBoxConfig.Error("配置连接失败或读取超时；请检查源是否可用")
        } finally {
            connection.disconnect()
        }
    }
}
