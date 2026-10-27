package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface TvBoxLiveResult {
    data class Ready(val channels: List<LiveChannel>) : TvBoxLiveResult
    data class Error(val reason: String) : TvBoxLiveResult
}

/** Offline-safe IPTV reader: JSON inline channels, M3U channel lists and TVBox group TXT. */
internal object TvBoxLiveCodec {
    private const val LIMIT = 250
    private val group = Regex("""group-title="([^"]{0,60})"""")
    private val tvgId = Regex("""tvg-id="([^"]{0,80})"""")

    private fun streamUrl(raw: String, allowHttp: Boolean): String? = TvBoxUrl.normalize(raw, allowHttp)

    fun inlineChannels(live: JSONObject, allowHttp: Boolean): List<LiveChannel> {
        val results = ArrayList<LiveChannel>()
        val seen = HashSet<String>()
        val groups = live.optJSONArray("channels") ?: return emptyList()
        val groupName = live.optString("name").trim().take(60)
        for (i in 0 until minOf(groups.length(), LIMIT)) {
            val entry = groups.optJSONObject(i) ?: continue
            val name = entry.optString("name").trim().take(80)
            if (name.isBlank()) continue
            val urls = entry.optJSONArray("urls") ?: JSONArray().put(entry.optString("url"))
            for (j in 0 until minOf(urls.length(), 8)) {
                val source = when (val value = urls.opt(j)) {
                    is JSONObject -> value.optString("url")
                    is String -> value
                    else -> ""
                }
                val url = streamUrl(source, allowHttp) ?: continue
                if (!seen.add(url)) continue
                results += LiveChannel(name, url, groupName, entry.optString("tvg-id").take(80))
                if (results.size == LIMIT) return results
            }
        }
        return results
    }

    /** No nested subscriptions or scripts are fetched from a downloaded JSON payload. */
    fun decode(raw: String, allowHttp: Boolean): List<LiveChannel> {
        require(raw.toByteArray(Charsets.UTF_8).size <= 512_000) { "直播配置大于 512 KB" }
        val text = raw.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val channels = when {
            text.startsWith("{") -> {
                val root = JSONObject(text)
                val lives = root.optJSONArray("lives")
                val sources = if (lives != null) lives else JSONArray().put(root)
                (0 until minOf(sources.length(), 100)).flatMap { index ->
                    sources.optJSONObject(index)?.let { inlineChannels(it, allowHttp) }.orEmpty()
                }
            }
            text.startsWith("#EXTM3U") -> m3u(text, allowHttp)
            else -> txt(text, allowHttp)
        }
        val unique = channels.distinctBy { it.url }.take(LIMIT)
        require(unique.isNotEmpty()) { "直播内容中没有可导入的 HTTP(S) 频道" }
        return unique
    }

    private fun m3u(text: String, allowHttp: Boolean): List<LiveChannel> {
        require(!text.contains("#EXT-X-", ignoreCase = true)) { "HLS 视频清单不是 IPTV 频道列表" }
        val result = ArrayList<LiveChannel>()
        var title = ""
        var groupName = ""
        var epgId = ""
        for (line in text.lineSequence().drop(1).map { it.trim() }) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                // Label follows the last unquoted comma, including attributes with commas.
                var quoted = false
                var separator = -1
                line.forEachIndexed { index, ch ->
                    if (ch == '"') quoted = !quoted
                    if (ch == ',' && !quoted) separator = index
                }
                title = if (separator < 0) "" else line.substring(separator + 1).trim().take(80)
                groupName = group.find(line)?.groupValues?.get(1).orEmpty()
                epgId = tvgId.find(line)?.groupValues?.get(1).orEmpty()
            } else if (line.isNotBlank() && !line.startsWith('#')) {
                val url = streamUrl(line, allowHttp)
                if (title.isNotBlank() && url != null) result += LiveChannel(title, url, groupName, epgId)
                title = ""
                groupName = ""
                epgId = ""
                if (result.size >= LIMIT) break
            }
        }
        return result
    }

    private fun txt(text: String, allowHttp: Boolean): List<LiveChannel> {
        val result = ArrayList<LiveChannel>()
        var groupName = ""
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.isBlank() || line.startsWith('#')) continue
            val comma = line.indexOf(',')
            if (comma !in 1..100) continue
            val title = line.substring(0, comma).trim().take(80)
            val address = line.substring(comma + 1).trim()
            if (address.equals("#genre#", ignoreCase = true)) {
                groupName = title.take(60)
                continue
            }
            val url = streamUrl(address, allowHttp)
            if (title.isNotBlank() && url != null) result += LiveChannel(title, url, groupName)
            if (result.size >= LIMIT) break
        }
        return result
    }
}

/** Strict bounded reader: an explicit TVBox live URL, never third-party scripts. */
internal class TvBoxLiveRepository {
    suspend fun load(rawUrl: String, allowHttp: Boolean): TvBoxLiveResult = withContext(Dispatchers.IO) {
        val url = TvBoxUrl.normalize(rawUrl, allowHttp)
            ?: return@withContext TvBoxLiveResult.Error("直播源需使用有效 HTTP(S) 地址；HTTP 需本次明确允许")
        val connection = try { URL(url).openConnection() as HttpURLConnection }
        catch (_: Exception) { return@withContext TvBoxLiveResult.Error("直播源连接失败") }
        try {
            connection.connectTimeout = 9_000
            connection.readTimeout = 9_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/plain, application/json, audio/x-mpegurl, */*")
            if (connection.responseCode != 200) return@withContext TvBoxLiveResult.Error("直播源 HTTP ${connection.responseCode}")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("text/html"))
                return@withContext TvBoxLiveResult.Error("直播源返回图片或网页，无法作为频道列表")
            if (connection.contentLengthLong > 512_000) return@withContext TvBoxLiveResult.Error("直播配置超过 512 KB")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 512_000) return@withContext TvBoxLiveResult.Error("直播配置超过 512 KB")
                    output.write(buffer, 0, count)
                }
            }
            TvBoxLiveResult.Ready(TvBoxLiveCodec.decode(output.toString(Charsets.UTF_8.name()), allowHttp))
        } catch (_: Exception) {
            TvBoxLiveResult.Error("无法识别直播列表；支持 TVBox JSON / M3U / TXT")
        } finally { connection.disconnect() }
    }
}

/** Selected user-approved channels survive app restarts without auto-running remote adapters. */
internal class TvBoxLiveStore(private val context: Context) {
    private val file get() = java.io.File(context.filesDir, "tvbox-selected-live.json")

    fun save(channels: List<LiveChannel>, allowHttp: Boolean): Boolean = runCatching {
        val safe = channels.filter { TvBoxUrl.normalize(it.url, allowHttp) != null }.take(250)
        require(safe.isNotEmpty())
        val array = JSONArray()
        safe.forEach { channel ->
            array.put(JSONObject().put("name", channel.name.take(80))
                .put("url", channel.url).put("group", channel.group.take(60))
                .put("tvgId", channel.tvgId.take(80)))
        }
        val json = JSONObject().put("v", 1).put("allowHttp", allowHttp).put("channels", array).toString()
        require(json.toByteArray().size <= 150_000)
        val temp = java.io.File(file.parentFile, "tvbox-selected-live.tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(file)) { file.writeText(json, Charsets.UTF_8); temp.delete() }
        true
    }.getOrDefault(false)

    fun load(): List<LiveChannel> = runCatching {
        if (!file.isFile || file.length() !in 1..150_000) return@runCatching emptyList()
        val objectJson = JSONObject(file.readText(Charsets.UTF_8))
        if (objectJson.optInt("v") != 1) return@runCatching emptyList()
        val allowHttp = objectJson.optBoolean("allowHttp")
        val array = objectJson.optJSONArray("channels") ?: return@runCatching emptyList()
        (0 until minOf(250, array.length())).mapNotNull { index ->
            array.optJSONObject(index)?.let { c ->
                val url = TvBoxUrl.normalize(c.optString("url"), allowHttp)
                val name = c.optString("name").take(80)
                if (url == null || name.isBlank()) null
                else LiveChannel(name, url, c.optString("group").take(60), c.optString("tvgId").take(80))
            }
        }
    }.getOrDefault(emptyList())
}
