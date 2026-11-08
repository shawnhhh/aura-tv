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
    data class Error(val reason: String, val httpCode: Int? = null, val retryAfterMs: Long? = null) : TvBoxVodResult
}

internal sealed interface TvBoxVodDetailResult {
    data class Ready(val film: Film) : TvBoxVodDetailResult
    data class Error(val reason: String, val httpCode: Int? = null, val retryAfterMs: Long? = null, val outcome: SourceSearchOutcome = SourceSearchOutcome.ERROR) : TvBoxVodDetailResult
}

/** The catalog call is not the detail call: TVBox CMS often omits play URLs on list pages. */
internal object TvBoxCmsEndpoint {
    private val replaced = setOf("ac", "pg", "ids", "id", "wd", "t", "pagesize")

    fun address(api: String, vodId: String? = null, allowHttp: Boolean, action: String = "detail"): String? {
        val base = TvBoxUrl.normalize(api, allowHttp) ?: return null
        val safeId = vodId?.takeIf { it.isNotBlank() && it.length <= 4096 }
        if (vodId != null && safeId == null) return null
        val root = URI(base)
        // Preserve the *raw* existing query: a URI component constructor would
        // double-escape percent-encoded CMS credentials such as token=a%2Fb.
        val query = root.rawQuery.orEmpty().split('&').filter { entry ->
            entry.isNotBlank() && entry.substringBefore('=').lowercase() !in replaced
        }.toMutableList()
        query += "ac=$action"
        if (safeId == null) query += "pg=1"
        else query += "ids=${URLEncoder.encode(safeId, Charsets.UTF_8.name())}"
        return TvBoxUrl.normalize("${root.scheme}://${root.rawAuthority}${root.rawPath.orEmpty()}?${query.joinToString("&")}", allowHttp)
    }
    /** FongMi type-4 HTTP Spider API uses wd/quick/extend, not ac=detail. */
    fun siteAddress(site: TvBoxSite, title: String? = null, vodId: String? = null, allowHttp: Boolean): String? {
        if(site.apiType != 4) {
            val action = if(site.apiType == 0) "videolist" else "detail"
            return if(title != null) searchAddress(site.apiUrl.orEmpty(), title, allowHttp, action)
                else address(site.apiUrl.orEmpty(), vodId, allowHttp, action)
        }
        val normalized = TvBoxUrl.normalize(site.apiUrl.orEmpty(), allowHttp) ?: return null
        val uri = URI(normalized)
        val query = uri.rawQuery.orEmpty().split('&').filter {
            it.isNotBlank() && it.substringBefore('=').lowercase() !in replaced + setOf("quick", "extend")
        }.toMutableList()
        fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        if(title != null) {
            if(title.trim().length !in 2..120) return null
            query += "wd=" + encode(title.trim())
            query += "quick=false"
        } else {
            query += "ac=detail"
            if(vodId != null) query += "ids=" + encode(vodId) else query += "pg=1"
        }
        query += "extend=" + encode(site.extension)
        return TvBoxUrl.normalize("${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}?${query.joinToString("&")}", allowHttp)
    }
    fun playerRequest(site: TvBoxSite, flag: String, play: String, allowHttp: Boolean): okhttp3.Request? {
        if(site.apiType != 4 || play.isBlank() || play.length > 2048) return null
        val normalized=TvBoxUrl.normalize(site.apiUrl.orEmpty(),allowHttp) ?: return null
        val uri=URI(normalized)
        val query=uri.rawQuery.orEmpty().split('&').filter {
            it.isNotBlank() && it.substringBefore('=').lowercase() !in replaced + setOf("quick","extend","play","flag")
        }.toMutableList()
        val builder=okhttp3.Request.Builder().apply { site.headers.forEach{(k,v)->header(k,v)} }
        if(site.extension.length > 1000) {
            builder.post(okhttp3.FormBody.Builder().add("play",play).add("flag",flag).add("extend",site.extension).build())
        } else {
            fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name())
            query += "play="+encode(play); query += "flag="+encode(flag); query += "extend="+encode(site.extension)
        }
        val endpoint="${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}" + if(query.isEmpty()) "" else "?${query.joinToString("&")}"
        return builder.url(endpoint).build()
    }
    fun siteRequest(site: TvBoxSite, title: String? = null, vodId: String? = null, allowHttp: Boolean): okhttp3.Request? {
        val builder = okhttp3.Request.Builder().apply { site.headers.forEach { (key,value) -> header(key,value) } }
        if(site.apiType != 4 || site.extension.length <= 1000) {
            return siteAddress(site,title,vodId,allowHttp)?.let { builder.url(it).build() }
        }
        val normalized = TvBoxUrl.normalize(site.apiUrl.orEmpty(),allowHttp) ?: return null
        val uri = URI(normalized)
        val query = uri.rawQuery.orEmpty().split('&').filter {
            it.isNotBlank() && it.substringBefore('=').lowercase() !in replaced + setOf("quick","extend")
        }.joinToString("&")
        val endpoint = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}" + if(query.isBlank()) "" else "?$query"
        val form = okhttp3.FormBody.Builder().add("extend",site.extension)
        if(title != null) {
            if(title.trim().length !in 2..120) return null
            form.add("wd",title.trim()).add("quick","false")
        } else {
            form.add("ac","detail")
            if(vodId != null) form.add("ids",vodId) else form.add("pg","1")
        }
        return builder.url(endpoint).post(form.build()).build()
    }
    /** Search a configured CMS by title; never a broad spider/JAR scan. */
    fun searchAddress(api: String, title: String, allowHttp: Boolean, action: String = "detail"): String? {
        val keyword = title.trim().take(120)
        if (keyword.length < 2) return null
        val catalog = address(api, allowHttp = allowHttp, action = action) ?: return null
        return catalog + "&wd=" + URLEncoder.encode(keyword, Charsets.UTF_8.name())
    }

}

/** Standard TVBox-compatible CMS JSON list. No Spider/JAR/JS is ever evaluated. */
internal object TvBoxVodCodec {
    fun decode(json: String, allowHttp: Boolean = false, allowOpaque: Boolean = false): List<Film> {
        require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "影视数据过大" }
        val items = JSONObject(json).optJSONArray("list") ?: error("缺少 CMS list 列表")
        return (0 until minOf(60, items.length())).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("vod_id").trim()
            val title = item.optString("vod_name").trim().take(100)
            if (id.length !in 1..4096 || title.isEmpty()) return@mapNotNull null
            val artwork = TvBoxUrl.normalize(item.optString("vod_pic"), allowHttp)
            val episodes = decodeEpisodes(item, allowHttp, allowOpaque)
            Film(id = "tvbox-$index-${id.hashCode().toUInt().toString(16)}", title = title,
                subtitle = item.optString("vod_remarks").take(80),
                category = TvBoxCategories.topLevel(item.optString("type_name")),
                year = item.optString("vod_year").take(10),
                synopsis = SourceText.synopsis(item.optString("vod_content")),
                artwork = R.drawable.photofilm_orbit, artworkUrl = artwork,
                backdropUrl = SourceArtwork.backdrop(item, allowHttp),
                episodes = maxOf(1, episodes.size), playbackEpisodes = episodes,
                cmsVodId = id)
        }.also { DiscoveryProtocolTrace.record("cms-codec","CMS_JSON",items.length(),it.size) }
    }

    fun detail(json: String, film: Film, allowHttp: Boolean, allowOpaque: Boolean = false): Film {
        val id = film.cmsVodId ?: error("缺少 CMS 原始 vod_id")
        val candidates = decode(json, allowHttp, allowOpaque)
        // A CMS endpoint can return a single *different* VOD when ids= is
        // ignored or rewritten by a gateway. Never play that wrong movie.
        // The selected source ID is authoritative, not array cardinality.
        val found = candidates.firstOrNull { it.cmsVodId == id }
            ?: error("CMS 详情未返回所选影片")
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

    private fun decodeEpisodes(item: JSONObject, allowHttp: Boolean, allowOpaque: Boolean): List<PlaybackEpisode> {
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
                val url = if(allowOpaque) address.takeIf { it.isNotBlank() && it.length <= 2048 }
                    else TvBoxUrl.normalize(address, allowHttp)
                if(url == null) return@forEachIndexed
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

/**
 * CMS VOD entries are different from version-1 HTTPS-only user catalogs.
 * A user-applied TVBox CMS may explicitly permit HTTP; the old generic
 * LicensedPlaybackResolver rejected these already-validated URLs at play time.
 *
 * Keep the selected episode and line exact. Do not attempt to interpret JX
 * pages as media URLs or forward site credentials to an unrelated CDN.
 */
internal object TvBoxCmsPlaybackResolver {
    fun resolve(
        film: Film, episode: Int, line: String, allowHttp: Boolean,
        siteHeaders: Map<String, String> = emptyMap()
    ): PlaybackResult {
        if (film.cmsVodId.isNullOrBlank())
            return PlaybackResult.Unavailable("当前影片不是 CMS 影视条目")
        val selected = film.playbackEpisodes.firstOrNull { it.number == episode }
            ?.lines?.firstOrNull { it.id == line }
            ?: return PlaybackResult.Unavailable("请选择此集有效的 CMS 播放线路")
        val url = TvBoxUrl.normalize(selected.uri, allowHttp)
            ?: return PlaybackResult.Unavailable("该线路不是允许访问的 HTTP(S) 媒体地址")
        val path = java.net.URI(url).path.orEmpty().lowercase(java.util.Locale.ROOT)
        if (path.endsWith(".html") || path.endsWith(".htm"))
            return PlaybackResult.Unavailable("该线路返回网页解析入口，而非视频文件")
        // CMS site authentication is scoped to its API. A selected video may
        // be hosted on a different CDN: never send its Cookie/Authorization.
        val publicHeaders = siteHeaders.filterKeys { key ->
            key.equals("User-Agent", ignoreCase = true) ||
                key.equals("Referer", ignoreCase = true)
        }
        return PlaybackResult.Ready(PlaybackMedia(
            uri = url, headers = publicHeaders, subtitles = selected.subtitles,
            allowHttpRedirects = allowHttp
        ))
    }
}

internal class TvBoxVodRepository {
    suspend fun load(site: TvBoxSite, allowHttp: Boolean): TvBoxVodResult = withContext(Dispatchers.IO) {
        val endpoint = TvBoxCmsEndpoint.siteAddress(site, allowHttp = allowHttp)
            ?: return@withContext TvBoxVodResult.Error("该站点不是可识别的 HTTP(S) CMS，或尚未授权 HTTP")
        try {
            val films = TvBoxVodCodec.decode(responseJson(fetchJson(endpoint,site.headers),site), allowHttp, allowOpaque = site.apiType == 4)
            if (films.isEmpty()) TvBoxVodResult.Error("接口没有返回可识别的影视条目")
            else TvBoxVodResult.Ready(films)
        } catch (error: Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            TvBoxVodResult.Error("CMS 列表读取失败：${error.message.orEmpty().take(110)}")
        }
    }

    /** Metadata-first discovery: bounded title search on an explicitly configured CMS. */
    suspend fun search(site: TvBoxSite, title: String, allowHttp: Boolean): TvBoxVodResult =
        withContext(Dispatchers.IO) {
            val request = TvBoxCmsEndpoint.siteRequest(site, title = title, allowHttp = allowHttp)
                ?: return@withContext TvBoxVodResult.Error("CMS 不支持该搜索地址")
            try {
                TvBoxVodResult.Ready(TvBoxVodCodec.decode(responseJson(fetchJson(request),site), allowHttp, allowOpaque = site.apiType == 4))
            } catch (error: Exception) {
                if(error is kotlinx.coroutines.CancellationException)throw error
                TvBoxVodResult.Error("CMS 片名搜索失败",(error as? SourceHttpFailure)?.code,(error as? SourceHttpFailure)?.retryAfterMs)
            }
        }

    /** Called on selection, never for every poster in the catalog. */
    suspend fun details(site: TvBoxSite, film: Film, allowHttp: Boolean): TvBoxVodDetailResult = withContext(Dispatchers.IO) {
        val id = film.cmsVodId
            ?: return@withContext TvBoxVodDetailResult.Error("当前影片没有 CMS 原始编号")
        val request = TvBoxCmsEndpoint.siteRequest(site, vodId = id, allowHttp = allowHttp)
            ?: return@withContext TvBoxVodDetailResult.Error("CMS 详情地址无效")
        try {
            val updated = TvBoxVodCodec.detail(responseJson(fetchJson(request),site), film, allowHttp, allowOpaque = site.apiType == 4)
            if (updated.playbackEpisodes.isEmpty())
                TvBoxVodDetailResult.Error("CMS 详情未提供直链播放源；此站点可能需要 TVBox 外部解析器",outcome=SourceSearchOutcome.NO_EPISODES)
            else TvBoxVodDetailResult.Ready(updated)
        } catch (error: Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            TvBoxVodDetailResult.Error("CMS 详情读取失败：${error.javaClass.simpleName}",(error as? SourceHttpFailure)?.code,(error as? SourceHttpFailure)?.retryAfterMs)
        }
    }

    suspend fun playerContent(site: TvBoxSite, film: Film, episode: Int, line: String, allowHttp: Boolean): String? = withContext(Dispatchers.IO) {
        if(film.cmsVodId == null) return@withContext null
        val selected=film.playbackEpisodes.firstOrNull{it.number==episode}?.lines?.firstOrNull{it.id==line}
            ?:return@withContext null
        val request=TvBoxCmsEndpoint.playerRequest(site,selected.title,selected.uri,allowHttp) ?:return@withContext null
        fetchJson(request)
    }

    private fun responseJson(content: String, site: TvBoxSite) =
        if(site.apiType == 0 && content.trimStart().startsWith("<")) TvBoxCmsXml.toJson(content) else content

    private suspend fun fetchJson(endpoint: String, headers: Map<String,String>): String {
        val request=okhttp3.Request.Builder().url(endpoint).apply{headers.forEach{(k,v)->header(k,v)}}
            .header("Accept","application/json, text/plain").build()
        return fetchJson(request)
    }

    private suspend fun fetchJson(request: okhttp3.Request): String {
        return readSourceResponse(client,request.newBuilder().header("Accept","application/json, text/plain").build()) { response ->
            if(response.code!=200)throw SourceHttpFailure(response.code,SourceHealthIndex.retryAfter(response.header("Retry-After"),System.currentTimeMillis()))
            val mime = response.header("Content-Type").orEmpty().lowercase()
            check(!mime.startsWith("image/") && !mime.startsWith("text/html") &&
                !mime.startsWith("video/")) { "影视接口未返回 JSON" }
            val body=requireNotNull(response.body)
            check(body.contentLength() <= 1_048_576) { "影视结果超过 1 MB" }
            val output = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1_048_576) { "影视结果超过 1 MB" }
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
    companion object { private val client=okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(9,java.util.concurrent.TimeUnit.SECONDS).readTimeout(9,java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10,java.util.concurrent.TimeUnit.SECONDS).build() }
}
