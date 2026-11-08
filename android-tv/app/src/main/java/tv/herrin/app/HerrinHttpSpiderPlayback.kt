package tv.herrin.app

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** HTTP type-4 playerContent uses the existing FongMi ParseJob, only after selection. */
internal suspend fun resolveHttpSpiderPlayback(context: Context, site: TvBoxSite, film: Film,
    episode: Int, line: String, config: TvBoxConfig.Single?, allowHttp: Boolean): PlaybackResult =
    withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(15_000) {
                val selected=film.playbackEpisodes.firstOrNull{it.number==episode}?.lines?.firstOrNull{it.id==line}
                    ?:return@withTimeoutOrNull PlaybackResult.Unavailable("当前影视点没有此选集")
                val raw=TvBoxVodRepository().playerContent(site,film,episode,line,allowHttp)
                    ?:return@withTimeoutOrNull PlaybackResult.Unavailable("HTTP Spider 未提供播放响应")
                val root=org.json.JSONObject(raw)
                // Never forward saved API Cookie/Authorization to an unrelated CDN.
                val safe=FongMiParsePlayback.safeHeaders(site.headers,site.apiUrl.orEmpty(),root.optString("url"))
                if(root.optJSONObject("header") == null) root.put("header",org.json.JSONObject(safe))
                FongMiParsePlayback.resolve(context,root.toString(),selected.title,
                    config?.jsonParsers.orEmpty(),allowHttp,siteHeaders=safe,
                    playbackParsers=config?.playbackParsers.orEmpty(),siteClick=site.click)
            } ?: PlaybackResult.Unavailable("HTTP Spider 播放解析超时")
        } catch(cancelled: CancellationException) { throw cancelled
        } catch(_: Exception) { PlaybackResult.Unavailable("HTTP Spider 播放响应失败；可换线路或返回选集") }
    }

@Composable
internal fun HttpSpiderPlayback(site: TvBoxSite, film: Film, episode: Int, line: String,
    config: TvBoxConfig.Single?, allowHttp: Boolean, retry: Int): PlaybackResult? {
    val context=androidx.compose.ui.platform.LocalContext.current
    var result by remember(site,film.id,episode,line,retry) { mutableStateOf<PlaybackResult?>(null) }
    LaunchedEffect(site,film.id,episode,line,retry) {
        result=resolveHttpSpiderPlayback(context,site,film,episode,line,config,allowHttp)
    }
    return result
}
