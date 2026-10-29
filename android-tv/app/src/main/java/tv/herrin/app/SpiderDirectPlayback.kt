package tv.herrin.app

import java.util.Locale

/**
 * LAST-RESORT playback for a source that already returned an explicit HTTP(S)
 * media URL in vod_play_url. Never guess, scrape, sniff or resolve opaque IDs.
 *
 * A crashing Spider should not prevent a user-authorized, already-resolved
 * direct media item from reaching Media3. Other Spider IDs still require the
 * real playerContent and keep the original error.
 */
internal object SpiderDirectPlayback {
    fun resolve(film: Film, episode: Int, line: String, site: TvBoxSite,
                allowHttp: Boolean): PlaybackResult.Ready? {
        // Do not let a previous site's credentials accompany a different film.
        val sitePrefix = "spider-" + site.key.hashCode().toUInt().toString(16) + "-"
        if (!film.id.startsWith(sitePrefix)) return null
        val selected = film.playbackEpisodes.firstOrNull { it.number == episode }
            ?.lines?.firstOrNull { it.id == line } ?: return null
        val url = TvBoxUrl.normalize(selected.uri, allowHttp) ?: return null
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val mime = when {
            path.endsWith(".m3u8") -> PlaybackFormatRetry.HLS
            path.endsWith(".mpd") -> PlaybackFormatRetry.DASH
            path.endsWith(".mp4") -> PlaybackFormatRetry.MP4
            else -> return null
        }
        return PlaybackResult.Ready(PlaybackMedia(
            uri = url, headers = site.headers, allowHttpRedirects = allowHttp,
            declaredMime = mime
        ))
    }

    fun eligibleFailure(message: String): Boolean =
        message.contains("Spider 子进程异常退出") ||
            message.contains("Spider 服务响应超时") ||
            message.contains("Spider 服务已断开")
}
