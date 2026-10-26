package tv.herrin.app

import org.json.JSONObject

/**
 * Explicit per-title playback metadata from a catalog that the user configured and is
 * authorized to use. HERRIN never guesses episode URLs or extracts them from websites.
 */
data class PlaybackLine(val id: String, val title: String, val uri: String)
data class PlaybackEpisode(val number: Int, val title: String, val lines: List<PlaybackLine>)

object PlaybackCatalogJson {
    private val identifier = Regex("^[A-Za-z0-9._-]{1,64}$")

    /**
     * Optional version 1 schema fragment:
     * "playback": { "episodes": [
     *   { "number": 1, "title": "第一集",
     *     "lines": [{ "id": "official", "title": "官方线路",
     *                 "url": "https://example.test/episode-1.m3u8" }] }
     * ] }
     */
    fun decode(film: JSONObject): List<PlaybackEpisode> {
        if (!film.has("playback") || film.isNull("playback")) return emptyList()
        val playback = film.getJSONObject("playback")
        val entries = playback.getJSONArray("episodes")
        require(entries.length() in 1..99) { "已配置的播放集数必须为 1–99" }
        val numbers = HashSet<Int>()
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val number = item.getInt("number")
            require(number in 1..99 && numbers.add(number)) { "集数重复或超出范围" }
            val title = item.optString("title", "第 ${number} 集").trim()
            require(title.isNotEmpty() && title.length <= 80) { "选集标题无效" }
            val options = item.getJSONArray("lines")
            require(options.length() in 1..8) { "每集应配置 1–8 条授权播放线路" }
            val ids = HashSet<String>()
            val lines = List(options.length()) { j ->
                val variant = options.getJSONObject(j)
                val id = variant.getString("id").trim()
                require(id.matches(identifier) && ids.add(id)) { "线路 ID 重复或无效" }
                val label = variant.optString("title", id).trim()
                require(label.isNotEmpty() && label.length <= 64) { "线路名称无效" }
                val url = AuthorizedUrls.https(variant.getString("url"))
                require(url != null) { "线路必须使用合法 HTTPS 地址" }
                PlaybackLine(id, label, url)
            }
            PlaybackEpisode(number, title, lines)
        }.sortedBy { it.number }
    }
}

object LicensedPlaybackResolver {
    fun resolve(film: Film, episode: Int, line: String): PlaybackResult {
        val option = film.playbackEpisodes.firstOrNull { it.number == episode }
            ?: return PlaybackResult.Unavailable("此影片尚未配置对应集数的授权播放线路")
        val selected = option.lines.firstOrNull { it.id == line }
            ?: return PlaybackResult.Unavailable("请选择此集可用的授权播放线路")
        val url = AuthorizedUrls.https(selected.uri)
            ?: return PlaybackResult.Unavailable("播放地址无效")
        return PlaybackResult.Ready(PlaybackMedia(url))
    }
}
