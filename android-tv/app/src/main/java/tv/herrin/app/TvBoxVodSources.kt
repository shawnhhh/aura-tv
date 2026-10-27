package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal sealed interface TvBoxVodResult {
    data class Ready(val films: List<Film>) : TvBoxVodResult
    data class Error(val reason: String) : TvBoxVodResult
}

/** Standard TVBox-compatible CMS JSON list. No Spider/JAR/JS is ever evaluated. */
internal object TvBoxVodCodec {
    fun decode(json: String): List<Film> {
        require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "影视数据过大" }
        val items = JSONObject(json).optJSONArray("list") ?: error("缺少 CMS list 列表")
        return (0 until minOf(60, items.length())).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("vod_id").trim().take(80)
            val title = item.optString("vod_name").trim().take(100)
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val artwork = TvBoxUrl.normalize(item.optString("vod_pic"))
            val episodes = decodeEpisodes(item)
            Film(id = "tvbox-$index-${id.hashCode().toUInt().toString(16)}", title = title,
                subtitle = item.optString("vod_remarks").take(80),
                category = item.optString("type_name").take(40).ifBlank { "电影" },
                year = item.optString("vod_year").take(10),
                synopsis = item.optString("vod_content").replace(Regex("<[^>]*>"), " ").take(1200),
                artwork = R.drawable.photofilm_orbit, artworkUrl = artwork,
                episodes = maxOf(1, episodes.size), playbackEpisodes = episodes)
        }
    }

    private fun decodeEpisodes(item: JSONObject): List<PlaybackEpisode> {
        val play = item.optString("vod_play_url")
        if (play.isBlank()) return emptyList()
        val namedLines = item.optString("vod_play_from").split("$$$")
        val merged = LinkedHashMap<Int, MutableList<PlaybackLine>>()
        play.split("$$$").take(8).forEachIndexed { sourceIndex, playlist ->
            val name = namedLines.getOrNull(sourceIndex)?.take(50).orEmpty().ifBlank { "线路 ${sourceIndex + 1}" }
            playlist.split('#').take(99).forEachIndexed { episodeIndex, entry ->
                val address = entry.substringAfterLast('$', "").trim()
                // Other TVBox parsers may resolve proprietary schemes; native playback only takes HTTPS.
                val url = TvBoxUrl.normalize(address) ?: return@forEachIndexed
                merged.getOrPut(episodeIndex + 1) { mutableListOf() } += PlaybackLine(
                    "cms-$sourceIndex", name, url)
            }
        }
        return merged.entries.sortedBy { it.key }.map { (index, lines) ->
            PlaybackEpisode(index, "第 ${index} 集", lines)
        }
    }
}

internal class TvBoxVodRepository {
    suspend fun load(site: TvBoxSite, allowHttp: Boolean): TvBoxVodResult = withContext(Dispatchers.IO) {
        val api = TvBoxUrl.normalize(site.apiUrl.orEmpty(), allowHttp)
            ?: return@withContext TvBoxVodResult.Error("该站点需要脚本插件，或需要本次允许 HTTP")
        val endpoint = try {
            val uri = URI(api)
            val query = uri.rawQuery.orEmpty()
            URI(uri.scheme, uri.rawAuthority, uri.rawPath,
                listOf(query, "ac=detail", "pg=1").filter { it.isNotBlank() }.joinToString("&"), null).toASCIIString()
        } catch (_: Exception) {
            return@withContext TvBoxVodResult.Error("影视接口地址不合法")
        }
        val connection = try { URL(endpoint).openConnection() as HttpURLConnection }
        catch (_: Exception) { return@withContext TvBoxVodResult.Error("无法连接影视接口") }
        try {
            connection.connectTimeout = 9_000
            connection.readTimeout = 9_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            if (connection.responseCode != 200)
                return@withContext TvBoxVodResult.Error("影视接口 HTTP ${connection.responseCode}")
            val mime = connection.contentType.orEmpty().lowercase()
            if (mime.startsWith("image/") || mime.startsWith("text/html") || mime.startsWith("video/"))
                return@withContext TvBoxVodResult.Error("影视接口未返回 JSON")
            if (connection.contentLengthLong > 1_048_576)
                return@withContext TvBoxVodResult.Error("影视结果超过 1 MB")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576)
                        return@withContext TvBoxVodResult.Error("影视结果超过 1 MB")
                    output.write(buffer, 0, count)
                }
            }
            val films = TvBoxVodCodec.decode(output.toString(Charsets.UTF_8.name()))
            if (films.isEmpty()) TvBoxVodResult.Error("接口没有返回可识别的影视条目")
            else TvBoxVodResult.Ready(films)
        } catch (_: Exception) {
            TvBoxVodResult.Error("影视 JSON 无法解析；该源可能依赖 TVBox 特殊采集器")
        } finally { connection.disconnect() }
    }
}
