package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Playback stage only. Discovery/episode listing never probes a whole library. */
internal suspend fun verifySelectedPersonalMedia(media: PlaybackMedia): PlaybackResult = withContext(Dispatchers.IO) {
    try {
        val selected=withTimeoutOrNull(8_000) {
            val resolved = HerrinPersonalMediaProbe(strictHttpFailure=true).resolve(media)
                ?: return@withTimeoutOrNull null
            resolved.takeIf { HerrinRemoteMediaProbe(authorizeLocal={it==resolved.uri},strictHttpFailure=true).canRead(resolved) }
        }
        if(selected != null) PlaybackResult.Ready(selected)
        else PlaybackResult.Unavailable("PERSONAL_READ_FAILED")
    } catch(cancelled: CancellationException) { throw cancelled
    } catch(failure: SourceHttpFailure) {
        HerrinPlaybackDiagnosticJournal.record("PERSONAL", "HTTP_${failure.code}")
        PlaybackResult.Unavailable(if(failure.code in setOf(401,403)) "PERSONAL_AUTH_FAILED" else "PERSONAL_HTTP_FAILED")
    } catch(_: Exception) { PlaybackResult.Unavailable("PERSONAL_READ_FAILED") }
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

/** Reopen the saved connection per playback attempt, including process restart and line changes. */
@Composable
internal fun SelectedIndexedPersonalPlayback(id: String, retry: Int, streams: HerrinSmbSessions): PlaybackResult? {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var result by remember(id, retry) { mutableStateOf<PlaybackResult?>(null) }
    LaunchedEffect(id, retry) {
        result = try {
            val source = withContext(Dispatchers.IO) { PersonalLibrary(context).play(id, streams) }
            if (source is PlaybackResult.Ready) verifySelectedPersonalMedia(source.media) else source
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { PlaybackResult.Unavailable("PERSONAL_CONNECTION_FAILED") }
    }
    return result
}
