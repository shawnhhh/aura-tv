package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Playback-only adapter for FongMi/TV ParseJob routing semantics.
 *
 * Reference: FongMi/TV (fongmi) ParseJob.java at
 * 5e191aaced194e5cac5668cc91908ecb65336222, GPL-3.0.
 *
 * This adapter deliberately returns only a playback decision: it never writes
 * Film, artwork, metadata, catalog, TMDB or Compose state. Source-specific
 * scripts and browser parsers are not disguised as native video URLs.
 */
internal sealed interface FongMiPlayRoute {
    data class Direct(val playback: PlaybackResult.Ready) : FongMiPlayRoute
    data class JsonParser(val parser: TvBoxJsonParse, val originalUrl: String) : FongMiPlayRoute
    data class Unavailable(val message: String) : FongMiPlayRoute
}

internal object FongMiPlaybackRouter {
    private val mediaExtensions = setOf(".m3u8", ".mpd", ".mp4", ".m4v",
        ".mkv", ".mov", ".webm", ".ts", ".flv", ".mp3", ".aac")

    fun route(
        playerContent: String,
        flag: String,
        jsonParsers: List<TvBoxJsonParse>,
        allowHttp: Boolean,
        siteHeaders: Map<String, String> = emptyMap()
    ): FongMiPlayRoute {
        val response = runCatching { JSONObject(playerContent) }.getOrNull()
            ?: return FongMiPlayRoute.Unavailable("Spider 播放数据不是有效 JSON")
        val direct = runCatching {
            SpiderVodCodec.player(playerContent, allowHttp, siteHeaders)
        }.getOrElse { PlaybackResult.Unavailable("Spider 播放数据无效") }
        val sourceUrl = firstHttpUrl(response.opt("url"), allowHttp)
        val instruction = response.optString("playUrl").trim()
        // FongMi ParseJob prioritizes an explicit json: or parse: playUrl.
        if (instruction.startsWith("json:", ignoreCase = true)) {
            val endpoint = TvBoxUrl.normalize(instruction.substringAfter(':'), allowHttp)
                ?: return FongMiPlayRoute.Unavailable("Spider json: 解析器地址无效")
            if (sourceUrl == null)
                return FongMiPlayRoute.Unavailable("Spider 没有提供要解析的影片地址")
            return FongMiPlayRoute.JsonParser(TvBoxJsonParse("Spider JSON", endpoint), sourceUrl)
        }
        if (instruction.startsWith("parse:", ignoreCase = true)) {
            val name = instruction.substringAfter(':')
            val parser = jsonParsers.firstOrNull { it.name == name }
                ?: return FongMiPlayRoute.Unavailable("影视源没有提供指定的 JSON 解析器")
            if (sourceUrl == null)
                return FongMiPlayRoute.Unavailable("Spider 没有提供要解析的影片地址")
            return FongMiPlayRoute.JsonParser(parser, sourceUrl)
        }

        if (sourceUrl == null) return when (direct) {
            is PlaybackResult.Ready -> FongMiPlayRoute.Direct(direct)
            is PlaybackResult.Unavailable -> FongMiPlayRoute.Unavailable(direct.reason)
        }

        val filePath = URI(sourceUrl).path.orEmpty().lowercase(Locale.ROOT)
        val isMedia = mediaExtensions.any(filePath::endsWith) ||
            PlaybackFormatRetry.declaredMime(response.optString("format")) != null
        val isWebPage = filePath.endsWith(".html") || filePath.endsWith(".htm")
        val needsParser = response.optInt("parse", 0) != 0 || response.optInt("jx", 0) != 0
        // A signed extensionless direct stream with parse=0 must not be
        // hijacked by a flag parser. Only explicit parser intent or an
        // identifiable HTML watch page gets parsed.
        if (!isMedia && (needsParser || isWebPage)) {
            val parser = TvBoxJsonPlayback.choose(sourceUrl, flag, jsonParsers)
            if (parser != null) return FongMiPlayRoute.JsonParser(parser, sourceUrl)
            return FongMiPlayRoute.Unavailable(
                "当前线路需要解析，但此影视源没有可用的 JSON 解析器")
        }
        return when (direct) {
            is PlaybackResult.Ready -> FongMiPlayRoute.Direct(direct)
            is PlaybackResult.Unavailable -> FongMiPlayRoute.Unavailable(direct.reason)
        }
    }

    private fun firstHttpUrl(value: Any?, allowHttp: Boolean): String? {
        val entries: List<String> = when (value) {
            is String -> listOf(value)
            is JSONArray -> (0 until minOf(32, value.length())).map { value.optString(it) }
            is JSONObject -> {
                val choices = value.optJSONArray("values") ?: JSONArray()
                val urls = (0 until minOf(20, choices.length())).mapNotNull {
                    choices.optJSONObject(it)?.optString("v")
                }
                val index = value.optInt("position", 0)
                if (index in urls.indices) listOf(urls[index]) + urls.filterIndexed { i, _ -> i != index }
                else urls
            }
            else -> emptyList()
        }
        return entries.firstNotNullOfOrNull { TvBoxUrl.normalize(it, allowHttp) }
    }
}
