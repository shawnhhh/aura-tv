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
    data class BrowserParser(val parser: TvBoxPlaybackParser, val originalUrl: String) : FongMiPlayRoute
    data class JarParser(val parser: TvBoxPlaybackParser, val originalUrl: String, val flag: String) : FongMiPlayRoute
    data class Aggregate(val parsers: List<TvBoxPlaybackParser>, val originalUrl: String, val flag: String) : FongMiPlayRoute
    data class Unavailable(val message: String) : FongMiPlayRoute
}

internal object FongMiPlaybackRouter {

    fun route(
        playerContent: String,
        flag: String,
        jsonParsers: List<TvBoxJsonParse>,
        allowHttp: Boolean,
        siteHeaders: Map<String, String> = emptyMap(),
        playbackParsers: List<TvBoxPlaybackParser> = emptyList()
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
            if (sourceUrl == null)
                return FongMiPlayRoute.Unavailable("Spider 没有提供要解析的影片地址")
            val parser = playbackParsers.firstOrNull { it.name == name }
            if (parser != null) return select(parser, sourceUrl, flag, playbackParsers)
            val oldParser = jsonParsers.firstOrNull { it.name == name }
                ?: return FongMiPlayRoute.Unavailable("影视源没有提供指定的解析器")
            return FongMiPlayRoute.JsonParser(oldParser, sourceUrl)
        }

        if (sourceUrl == null) return when (direct) {
            is PlaybackResult.Ready -> FongMiPlayRoute.Direct(direct)
            is PlaybackResult.Unavailable -> FongMiPlayRoute.Unavailable(direct.reason)
        }

        val filePath = URI(sourceUrl).path.orEmpty().lowercase(Locale.ROOT)
        val isMedia = SpiderProxyPlayback.isCatVodProxy(sourceUrl) ||
            PlaybackFormatRetry.declaredMime(response.optString("format")) != null
        val isWebPage = filePath.endsWith(".html") || filePath.endsWith(".htm")
        val needsParser = response.optInt("parse", 0) != 0 || response.optInt("jx", 0) != 0
        // A signed extensionless direct stream with parse=0 must not be
        // hijacked by a flag parser. Only explicit parser intent or an
        // identifiable HTML watch page gets parsed.
        if (!isMedia && (needsParser || isWebPage)) {
            val matching = playbackParsers.firstOrNull { parser ->
                parser.type != 4 && parser.flags.any { it.equals(flag, true) }
            } ?: playbackParsers.firstOrNull { parser ->
                parser.type == 4 || (parser.type in 0..1 && parser.flags.isEmpty())
            }
            if (matching != null) return select(matching, sourceUrl, flag, playbackParsers)
            val legacy = TvBoxJsonPlayback.choose(sourceUrl, flag, jsonParsers)
            if (legacy != null) return FongMiPlayRoute.JsonParser(legacy, sourceUrl)
            return FongMiPlayRoute.Unavailable(
                "当前线路需要解析，但影视源没有提供可用的解析器")
        }
        return when (direct) {
            is PlaybackResult.Ready -> FongMiPlayRoute.Direct(direct)
            is PlaybackResult.Unavailable -> FongMiPlayRoute.Unavailable(direct.reason)
        }
    }

    private fun select(
        parser: TvBoxPlaybackParser, url: String, flag: String,
        candidates: List<TvBoxPlaybackParser> = emptyList()
    ): FongMiPlayRoute = when (parser.type) {
        0 -> FongMiPlayRoute.BrowserParser(parser, url)
        1 -> FongMiPlayRoute.JsonParser(parser.asJson(), url)
        2, 3 -> FongMiPlayRoute.JarParser(parser, url, flag)
        4 -> FongMiPlayRoute.Aggregate(candidates.filter { it.type in 0..1 &&
            (it.flags.isEmpty() || it.flags.any { f -> f.equals(flag, true) }) },
            url, flag)
        else -> FongMiPlayRoute.Unavailable("该线路的解析器类型不受支持")
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
