package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Playback stage only. Discovery/episode listing never probes a whole library. */
internal suspend fun verifySelectedPersonalMedia(media: PlaybackMedia): PlaybackResult = withContext(Dispatchers.IO) {
    try {
        val valid=withTimeoutOrNull(4_000) {
            HerrinPersonalMediaProbe(strictHttpFailure=true).canRead(media) &&
                HerrinRemoteMediaProbe(authorizeLocal={it==media.uri},strictHttpFailure=true).canRead(media)
        } == true
        if(valid) PlaybackResult.Ready(media)
        else PlaybackResult.Unavailable("个人媒体响应验证失败或超时；可重试、换线路或返回选集")
    } catch(cancelled: CancellationException) { throw cancelled
    } catch(failure: SourceHttpFailure) {
        PlaybackResult.Unavailable("个人媒体验证 HTTP ${failure.code}；请检查来源授权或选择其他线路")
    } catch(_: Exception) { PlaybackResult.Unavailable("个人媒体验证失败；可重试或返回选集") }
}

@Composable
internal fun SelectedPersonalPlayback(media: PlaybackMedia, retry: Int): PlaybackResult? {
    var result by remember(media,retry) { mutableStateOf<PlaybackResult?>(null) }
    LaunchedEffect(media,retry) {
        result=verifySelectedPersonalMedia(media)
        HerrinPlaybackDiagnosticJournal.record("SOURCE",
            if(result is PlaybackResult.Ready) "PERSONAL_MEDIA_VERIFIED" else "PERSONAL_MEDIA_REJECTED")
    }
    return result
}
