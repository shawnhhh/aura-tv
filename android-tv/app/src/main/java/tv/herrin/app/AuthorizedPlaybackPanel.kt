package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.Tracks

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
    var uiEpoch by remember(media.uri) { mutableIntStateOf(0) }
    var shownPosition by remember(media.uri) { mutableLongStateOf(0L) }
    var shownDuration by remember(media.uri) { mutableLongStateOf(0L) }
    val initialControl = remember { FocusRequester() }
    val latestProgressCallback by rememberUpdatedState(onPlaybackProgress)
    val player = remember(media.uri, media.subtitles) {
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
                override fun onTracksChanged(tracks: Tracks) { uiEpoch++ }
                override fun onIsPlayingChanged(isPlaying: Boolean) { uiEpoch++ }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { uiEpoch++ }
                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = "媒体播放失败：${error.errorCodeName}"
                }
            })
            val item = MediaItem.Builder().setUri(media.uri).apply {
                if (media.subtitles.isNotEmpty()) {
                    setSubtitleConfigurations(media.subtitles.map { subtitle ->
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage(subtitle.language)
                            .setLabel(subtitle.title)
                            .build()
                    })
                }
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
        var tick = 0
        while (true) {
            delay(500)
            shownPosition = player.currentPosition.coerceAtLeast(0L)
            shownDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            tick++
            if (tick % 5 == 0 &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED))
                reportProgress()
        }
    }
    LaunchedEffect(player) { initialControl.requestFocus() }
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
        } else if (key.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                PlaybackTransport.seekBy(player,
                    if (key.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 10_000L else -10_000L)
            }
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
    }) {
        AndroidView(
            factory = { ctx -> object : PlayerView(ctx) {
                // Native PlayerView otherwise consumes the first Back to hide its controller.
                // HERRIN treats Back as leaving playback, independent of controller visibility.
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) onBack()
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            PlaybackTransport.seekBy(player,
                                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 10_000L else -10_000L)
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                // One TV focus system: native view renders video and subtitles only;
                // the Compose row below provides seek, play, tracks and a progress timeline.
                useController = false
                isFocusable = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setPadding(32, 32, 32, 32)
                this.player = player
            } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onBack) { Text("返回详情") }
            Text("仅播放你有权使用的媒体 · 播放源按所选影片/线路确定", color = Color.White)
            if (errorMessage != null) Text(errorMessage!!, color = Color(0xFFFFD0D0))
        }
        // One visible, original HERRIN transport surface; no competing native overlay.
        val _observeTrackChanges = uiEpoch
        val audios = PlaybackTransport.options(player, C.TRACK_TYPE_AUDIO)
        val captions = PlaybackTransport.options(player, C.TRACK_TYPE_TEXT)
        val percent = if (shownDuration > 0L)
            (shownPosition.toDouble() / shownDuration.toDouble()).toFloat().coerceIn(0f, 1f)
            else 0f
        fun elapsed(ms: Long): String {
            val seconds = (ms / 1000L).coerceAtLeast(0L)
            return "%02d:%02d".format(seconds / 60L, seconds % 60L)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.80f).padding(bottom = 36.dp)
                .background(Color(0xE9151820), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(elapsed(shownPosition), color = Color.White)
                Text(elapsed(shownDuration), color = Color(0xFFD2D5DE))
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF434857))) {
                Box(Modifier.fillMaxWidth(percent).fillMaxHeight().background(Color.White))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlaybackTransport.seekBy(player, -10_000L) },
                    modifier = Modifier.testTag("transport-rewind")) { Text("−10s") }
                Button(onClick = { if (player.playWhenReady) player.pause() else player.play() },
                    modifier = Modifier.focusRequester(initialControl).testTag("transport-toggle")) {
                    Text(if (player.playWhenReady) "暂停" else "播放")
                }
                Button(onClick = { PlaybackTransport.seekBy(player, 10_000L) },
                    modifier = Modifier.testTag("transport-forward")) { Text("+10s") }
                Button(
                    onClick = { if (PlaybackTransport.cycle(player, C.TRACK_TYPE_AUDIO)) uiEpoch++ },
                    enabled = audios.size > 1, modifier = Modifier.testTag("transport-audio")
                ) { Text("音轨: " + PlaybackTransport.currentLabel(player, C.TRACK_TYPE_AUDIO)) }
                Button(
                    onClick = { if (PlaybackTransport.cycle(player, C.TRACK_TYPE_TEXT)) uiEpoch++ },
                    enabled = captions.isNotEmpty(), modifier = Modifier.testTag("transport-subtitle")
                ) { Text("字幕: " + PlaybackTransport.currentLabel(player, C.TRACK_TYPE_TEXT)) }
            }
        }
    }
}
