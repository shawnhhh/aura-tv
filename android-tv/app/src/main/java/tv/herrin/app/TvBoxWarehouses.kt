package tv.herrin.app

import android.content.Context
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
import java.io.File
import java.security.MessageDigest

/** TVBox metadata is untrusted: no Spider, JAR, JavaScript or site API is executed. */
internal data class TvBoxWarehouse(val name: String, val url: String)
internal data class TvBoxSite(val name: String, val needsAdapter: Boolean, val apiUrl: String? = null)
internal data class TvBoxLive(val name: String, val playlistUrl: String?,
                              val sourceUrl: String? = null, val channels: List<LiveChannel> = emptyList())

internal sealed interface TvBoxConfig {
    data class Warehouses(val entries: List<TvBoxWarehouse>, val ignored: Int, val fromCache: Boolean = false) : TvBoxConfig
    data class Single(val sites: List<TvBoxSite>, val lives: List<TvBoxLive>, val hasExternalEngine: Boolean,
                      val fromCache: Boolean = false, val wrappedInImage: Boolean = false) : TvBoxConfig
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
        val root = try { JSONObject(TvBoxWrappedConfig.stripComments(json)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')) }
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
                    if (title.isBlank()) null else {
                        val api = site.optString("api").trim()
                        val direct = TvBoxUrl.normalize(api, allowHttp = true)
                        TvBoxSite(title, api.isNotBlank() && direct == null, direct)
                    }
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
                        TvBoxLive(title, playlist,
                            sourceUrl = TvBoxUrl.normalize(address, allowHttp = true),
                            channels = TvBoxLiveCodec.inlineChannels(live, allowHttp = true))
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

internal class TvBoxConfigRepository(private val context: Context? = null) {
    private fun cacheFile(url: String): File? {
        val directory = context?.filesDir ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(directory, "tvbox-config"), "$digest.json")
    }

    private fun cached(url: String): TvBoxConfig? = runCatching {
        val file = cacheFile(url) ?: return@runCatching null
        if (!file.isFile || file.length() !in 1..1_048_576) return@runCatching null
        when (val parsed = TvBoxConfigParser.parse(file.readText(Charsets.UTF_8), url)) {
            is TvBoxConfig.Single -> parsed.copy(fromCache = true)
            is TvBoxConfig.Warehouses -> parsed.copy(fromCache = true)
            else -> null
        }
    }.getOrNull()

    private fun save(url: String, text: String) {
        val file = cacheFile(url) ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    /** Explicit import from a user-controlled backup, useful when the original URL has expired. */
    suspend fun importJson(json: String, sourceUrl: String, allowHttp: Boolean): TvBoxConfig = withContext(Dispatchers.IO) {
        val url = TvBoxUrl.normalize(sourceUrl, allowHttp)
            ?: return@withContext TvBoxConfig.Error("请先填写本次允许访问的 HTTP(S) 配置地址")
        if (json.toByteArray(Charsets.UTF_8).size !in 1..1_048_576)
            return@withContext TvBoxConfig.Error("本地 JSON 必须为 1 字节至 1 MB")
        when (val parsed = TvBoxConfigParser.parse(json, url)) {
            is TvBoxConfig.Single, is TvBoxConfig.Warehouses -> {
                runCatching { save(url, TvBoxWrappedConfig.stripComments(json)) }
                parsed
            }
            else -> TvBoxConfig.Error("只能缓存有效的 TVBox 单仓或多仓 JSON")
        }
    }

    suspend fun load(input: String, allowHttp: Boolean = false): TvBoxConfig = withContext(Dispatchers.IO) {
        val address = TvBoxUrl.normalize(input, allowHttp)
            ?: return@withContext TvBoxConfig.Error("只接受 HTTPS；如需读取 HTTP，请先手动开启本次授权")
        val connection = try { URL(address).openConnection() as HttpURLConnection }
        catch (_: Exception) { return@withContext cached(address) ?: TvBoxConfig.Error("无法建立配置连接") }
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in 300..399) return@withContext cached(address)
                ?: TvBoxConfig.Error("配置地址发生跳转；请检查最终地址后重新输入")
            if (status !in 200..299) return@withContext cached(address)
                ?: TvBoxConfig.Error("配置读取失败：HTTP $status")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.startsWith("video/") || contentType.startsWith("text/html")) {
                return@withContext cached(address)
                    ?: TvBoxConfig.Error("地址返回了网页或视频，不是 TVBox JSON 配置")
            }
            val maxBytes = 1024 * 1024
            if (connection.contentLengthLong > maxBytes) return@withContext cached(address)
                ?: TvBoxConfig.Error("配置超过 1 MB 安全限制")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { inputStream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (bytes.size() + read > maxBytes) return@withContext cached(address)
                        ?: TvBoxConfig.Error("配置超过 1 MB 安全限制")
                    bytes.write(buffer, 0, read)
                }
            }
            val wrapped = TvBoxWrappedConfig.extract(bytes.toByteArray())
            if (wrapped == null && contentType.startsWith("image/")) {
                return@withContext cached(address)
                    ?: TvBoxConfig.Error("地址返回图片，但未找到有效的 TVBox 尾随 Base64 配置")
            }
            val json = TvBoxWrappedConfig.stripComments(wrapped ?: bytes.toString(Charsets.UTF_8.name()))
            when (val parsed = TvBoxConfigParser.parse(json, address)) {
                is TvBoxConfig.Single, is TvBoxConfig.Warehouses -> {
                    runCatching { save(address, json) }
                    if (parsed is TvBoxConfig.Single && wrapped != null)
                        parsed.copy(wrappedInImage = true)
                    else parsed
                }
                is TvBoxConfig.Error -> cached(address) ?: parsed
                else -> parsed
            }
        } catch (_: Exception) {
            cached(address) ?: TvBoxConfig.Error("配置连接失败或读取超时；请检查源是否可用")
        } finally {
            connection.disconnect()
        }
    }
}
