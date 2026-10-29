package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal sealed interface TvBoxVodResult {
    data class Ready(val films: List<Film>) : TvBoxVodResult
    data class Error(val reason: String) : TvBoxVodResult
}

internal sealed interface TvBoxVodDetailResult {
    data class Ready(val film: Film) : TvBoxVodDetailResult
    data class Error(val reason: String) : TvBoxVodDetailResult
}

/** The catalog call is not the detail call: TVBox CMS often omits play URLs on list pages. */
internal object TvBoxCmsEndpoint {
    private val replaced = setOf("ac", "pg", "ids", "id", "wd", "t", "pagesize")

    fun address(api: String, vodId: String? = null, allowHttp: Boolean): String? {
        val base = TvBoxUrl.normalize(api, allowHttp) ?: return null
        val safeId = vodId?.takeIf { it.isNotBlank() && it.length <= 256 }
        if (vodId != null && safeId == null) return null
        val root = URI(base)
        // Preserve the *raw* existing query: a URI component constructor would
        // double-escape percent-encoded CMS credentials such as token=a%2Fb.
        val query = root.rawQuery.orEmpty().split('&').filter { entry ->
            entry.isNotBlank() && entry.substringBefore('=').lowercase() !in replaced
        }.toMutableList()
        query += "ac=detail"
        if (safeId == null) query += "pg=1"
        else query += "ids=${URLEncoder.encode(safeId, Charsets.UTF_8.name())}"
        return TvBoxUrl.normalize("${root.scheme}://${root.rawAuthority}${root.rawPath.orEmpty()}?${query.joinToString("&")}", allowHttp)
    }
}

/** Standard TVBox-compatible CMS JSON list. No Spider/JAR/JS is ever evaluated. */
internal object TvBoxVodCodec {
    fun decode(json: String, allowHttp: Boolean = false): List<Film> {
        require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "影视数据过大" }
        val items = JSONObject(json).optJSONArray("list") ?: error("缺少 CMS list 列表")
        return (0 until minOf(60, items.length())).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("vod_id").trim().take(256)
            val title = item.optString("vod_name").trim().take(100)
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val artwork = TvBoxUrl.normalize(item.optString("vod_pic"), allowHttp)
            val episodes = decodeEpisodes(item, allowHttp)
            Film(id = "tvbox-$index-${id.hashCode().toUInt().toString(16)}", title = title,
                subtitle = item.optString("vod_remarks").take(80),
                category = TvBoxCategories.topLevel(item.optString("type_name")),
                year = item.optString("vod_year").take(10),
                synopsis = SourceText.synopsis(item.optString("vod_content")),
                artwork = R.drawable.photofilm_orbit, artworkUrl = artwork,
                backdropUrl = SourceArtwork.backdrop(item, allowHttp),
                episodes = maxOf(1, episodes.size), playbackEpisodes = episodes,
                cmsVodId = id)
        }
    }

    fun detail(json: String, film: Film, allowHttp: Boolean): Film {
        val id = film.cmsVodId ?: error("缺少 CMS 原始 vod_id")
        val candidates = decode(json, allowHttp)
        val found = candidates.firstOrNull { it.cmsVodId == id }
            ?: candidates.singleOrNull() ?: error("CMS 详情未返回对应影片")
        return film.copy(
            subtitle = found.subtitle.ifBlank { film.subtitle },
            category = found.category.ifBlank { film.category },
            year = found.year.ifBlank { film.year },
            synopsis = found.synopsis.ifBlank { film.synopsis },
            artworkUrl = found.artworkUrl ?: film.artworkUrl,
            backdropUrl = found.backdropUrl ?: film.backdropUrl,
            episodes = maxOf(1, found.playbackEpisodes.size),
            playbackEpisodes = found.playbackEpisodes)
    }

    private fun decodeEpisodes(item: JSONObject, allowHttp: Boolean): List<PlaybackEpisode> {
        val play = item.optString("vod_play_url")
        if (play.isBlank()) return emptyList()
        val namedLines = item.optString("vod_play_from").split("$$$")
        val merged = LinkedHashMap<Int, MutableList<PlaybackLine>>()
        val titles = LinkedHashMap<Int, String>()
        play.split("$$$").take(12).forEachIndexed { sourceIndex, playlist ->
            val name = namedLines.getOrNull(sourceIndex)?.take(50).orEmpty().ifBlank { "线路 ${sourceIndex + 1}" }
            playlist.split('#').take(300).forEachIndexed { episodeIndex, entry ->
                val separator = entry.indexOf('$')
                val address = (if (separator >= 0) entry.substring(separator + 1) else entry).trim()
                // Honor the user's HTTP source preference for direct media too;
                // never treat JS/plugin/opaque identifiers as native media URLs.
                val url = TvBoxUrl.normalize(address, allowHttp) ?: return@forEachIndexed
                if (separator > 0 && !titles.containsKey(episodeIndex + 1))
                    titles[episodeIndex + 1] = entry.substring(0, separator).trim().take(80)
                merged.getOrPut(episodeIndex + 1) { mutableListOf() } += PlaybackLine(
                    "cms-$sourceIndex", name, url)
            }
        }
        return merged.entries.sortedBy { it.key }.map { (index, lines) ->
            PlaybackEpisode(index, titles[index]?.takeIf { it.isNotBlank() } ?: "第 ${index} 集", lines)
        }
    }
}

internal class TvBoxVodRepository {
    suspend fun load(site: TvBoxSite, allowHttp: Boolean): TvBoxVodResult = withContext(Dispatchers.IO) {
        val endpoint = TvBoxCmsEndpoint.address(site.apiUrl.orEmpty(), allowHttp = allowHttp)
            ?: return@withContext TvBoxVodResult.Error("该站点不是可识别的 HTTP(S) CMS，或尚未授权 HTTP")
        try {
            val films = TvBoxVodCodec.decode(fetchJson(endpoint), allowHttp)
            if (films.isEmpty()) TvBoxVodResult.Error("接口没有返回可识别的影视条目")
            else TvBoxVodResult.Ready(films)
        } catch (error: Exception) {
            TvBoxVodResult.Error("CMS 列表读取失败：${error.message.orEmpty().take(110)}")
        }
    }

    /** Called on selection, never for every poster in the catalog. */
    suspend fun details(site: TvBoxSite, film: Film, allowHttp: Boolean): TvBoxVodDetailResult = withContext(Dispatchers.IO) {
        val id = film.cmsVodId
            ?: return@withContext TvBoxVodDetailResult.Error("当前影片没有 CMS 原始编号")
        val endpoint = TvBoxCmsEndpoint.address(site.apiUrl.orEmpty(), id, allowHttp)
            ?: return@withContext TvBoxVodDetailResult.Error("CMS 详情地址无效")
        try {
            val updated = TvBoxVodCodec.detail(fetchJson(endpoint), film, allowHttp)
            if (updated.playbackEpisodes.isEmpty())
                TvBoxVodDetailResult.Error("CMS 详情未提供直链播放源；此站点可能需要 TVBox 外部解析器")
            else TvBoxVodDetailResult.Ready(updated)
        } catch (error: Exception) {
            TvBoxVodDetailResult.Error("CMS 详情读取失败：${error.message.orEmpty().take(110)}")
        }
    }

    private fun fetchJson(endpoint: String): String {
        val connection = try { URL(endpoint).openConnection() as HttpURLConnection }
        catch (error: Exception) { throw IllegalStateException("无法连接影视接口", error) }
        try {
            connection.connectTimeout = 9_000
            connection.readTimeout = 9_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            check(connection.responseCode == 200) { "影视接口 HTTP ${connection.responseCode}" }
            val mime = connection.contentType.orEmpty().lowercase()
            check(!mime.startsWith("image/") && !mime.startsWith("text/html") &&
                !mime.startsWith("video/")) { "影视接口未返回 JSON" }
            check(connection.contentLengthLong <= 1_048_576) { "影视结果超过 1 MB" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1_048_576) { "影视结果超过 1 MB" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }
}
