package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * M2: real Android TV playback for an explicitly configured, licensed HTTPS test stream.
 * Does not guess episode URLs or access third-party provider streams.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun AuthorizedPlaybackPanel(
    media: PlaybackMedia,
    onPlayer: (ExoPlayer) -> Unit = {},
    startPositionMs: Long = 0L,
    onPlaybackProgress: (positionMs: Long, durationMs: Long, finished: Boolean) -> Unit = { _, _, _ -> },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var errorMessage by remember(media.uri) { mutableStateOf<String?>(null) }
    val latestProgressCallback by rememberUpdatedState(onPlaybackProgress)
    val player = remember(media.uri) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(media.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        ExoPlayer.Builder(context).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            onPlayer(this)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = "媒体播放失败：${error.errorCodeName}"
                }
            })
            val item = MediaItem.Builder().setUri(media.uri).apply {
                when {
                    media.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    media.uri.substringBefore('?').endsWith(".mpd", ignoreCase = true) ->
                        setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }.build()
            setMediaItem(item)
            if (startPositionMs > 0L) seekTo(startPositionMs.coerceAtMost(12L * 60L * 60_000L))
            prepare()
            playWhenReady = true
        }
    }
    fun reportProgress() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        latestProgressCallback(player.currentPosition.coerceAtLeast(0L), duration,
            player.playbackState == Player.STATE_ENDED)
    }
    LaunchedEffect(player) {
        while (true) {
            delay(2500)
            if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)
                reportProgress()
        }
    }
    DisposableEffect(player, lifecycle) {
        val session = MediaSession.Builder(context, player).build()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { reportProgress(); player.pause() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            reportProgress()
            lifecycle.removeObserver(observer)
            session.release()
            player.stop()
            player.release()
        }
    }
    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode == KeyEvent.KEYCODE_BACK) {
            if (key.action == KeyEvent.ACTION_UP) onBack()
            true
        } else if (key.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                when (key.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                    else -> if (player.playWhenReady) player.pause() else player.play()
                }
            }
            true
        } else false
    }.focusable()) {
        AndroidView(
            factory = { ctx -> object : PlayerView(ctx) {
                // Native PlayerView otherwise consumes the first Back to hide its controller.
                // HERRIN treats Back as leaving playback, independent of controller visibility.
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) onBack()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                useController = true
                controllerAutoShow = true
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setPadding(32, 32, 32, 32)
                isFocusable = true
                requestFocus()
                this.player = player
            } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onBack) { Text("返回详情") }
            Text("用户授权的 HTTPS 测试媒体 · 不代表当前影片片源", color = Color.White)
            if (errorMessage != null) Text(errorMessage!!, color = Color(0xFFFFD0D0))
        }
    }
}
