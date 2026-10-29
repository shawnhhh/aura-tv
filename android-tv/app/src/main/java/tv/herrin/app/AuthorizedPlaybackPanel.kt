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
import androidx.compose.foundation.layout.size
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
import androidx.media3.datasource.HttpDataSource
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
    onMediaSpecDetected: (VerifiedStreamSpec) -> Unit = {},
    isLive: Boolean = false,
    onLiveMenu: () -> Unit = {},
    focusRestoreEpoch: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var errorMessage by remember(media) { mutableStateOf<String?>(null) }
    var playbackState by remember(media) { mutableIntStateOf(Player.STATE_IDLE) }
    var uiEpoch by remember(media) { mutableIntStateOf(0) }
    var activeVariant by remember(media) { mutableIntStateOf(0) }
    var overrideMime by remember(media) { mutableStateOf<String?>(null) }
    var shownPosition by remember(media.uri, media.headers, media.subtitles) { mutableLongStateOf(0L) }
    var shownDuration by remember(media.uri, media.headers, media.subtitles) { mutableLongStateOf(0L) }
    var showVerification by remember { mutableStateOf(false) }
    var verificationTick by remember { mutableIntStateOf(0) }
    val initialControl = remember { FocusRequester() }
    val latestProgressCallback by rememberUpdatedState(onPlaybackProgress)
    val latestMediaSpecCallback by rememberUpdatedState(onMediaSpecDetected)
    val player = remember(media) {
        val candidates = (listOf(media.uri) + media.alternateUris).distinct().take(8)
        fun itemFor(uri: String, mimeOverride: String? = null): MediaItem = MediaItem.Builder().setUri(uri).apply {
            if (media.subtitles.isNotEmpty()) {
                setSubtitleConfigurations(media.subtitles.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri))
                        .setMimeType(MimeTypes.TEXT_VTT).setLanguage(subtitle.language)
                        .setLabel(subtitle.title)
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                })
            }
            (mimeOverride ?: PlaybackFormatRetry.initialMime(uri))?.let(::setMimeType)
        }.build()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(media.allowHttpRedirects)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(media.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        ExoPlayer.Builder(context).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            onPlayer(this)
            addListener(object : Player.Listener {
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    uiEpoch++
                }
                override fun onTracksChanged(tracks: Tracks) { uiEpoch++ }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { uiEpoch++ }
                override fun onIsPlayingChanged(isPlaying: Boolean) { uiEpoch++ }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { uiEpoch++ }
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                    uiEpoch++
                }
                override fun onPlayerError(error: PlaybackException) {
                    val alternateMime = PlaybackFormatRetry.nextMime(
                        candidates[activeVariant], overrideMime, error.errorCode)
                    if (alternateMime != null) {
                        // Same source, no scraping: Media3 may have interpreted
                        // an extensionless HLS playlist as a progressive file.
                        overrideMime = alternateMime
                        errorMessage = null
                        setMediaItem(itemFor(candidates[activeVariant], alternateMime))
                        prepare()
                        play()
                    } else if (PlaybackFormatRetry.retryNextSource(error.errorCode) &&
                        activeVariant < candidates.lastIndex) {
                        // The Spider chose several authorized URLs. Retry the next
                        // supplied candidate after network, container or decoder
                        // failure, without discovering any new stream.
                        activeVariant++
                        overrideMime = null
                        errorMessage = null
                        setMediaItem(itemFor(candidates[activeVariant]))
                        prepare()
                        play()
                    } else {
                        val httpStatus = generateSequence(error.cause) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode
                        errorMessage = playbackErrorMessage(error.errorCodeName) +
                            "（${error.errorCodeName}${httpStatus?.let { ", HTTP $it" }.orEmpty()}" +
                            "; 线路 ${activeVariant + 1}/${candidates.size}）"
                    }
                }
            })
            setMediaItem(itemFor(candidates.first()))
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
    fun retryPlayback() {
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        errorMessage = null
        player.prepare()
        if (resumePosition > 0L) player.seekTo(resumePosition)
        player.play()
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
    // No network probes on the detail screen. Read only selected Media3 formats
    // after the player itself has opened the exact user-selected source.
    LaunchedEffect(player, uiEpoch) {
        fun selected(type: Int): SelectedTrackEvidence? = player.currentTracks.groups
            .asSequence().filter { it.type == type }.flatMap { group ->
                (0 until group.length).asSequence().filter { group.isTrackSelected(it) }
                    .map { group.getTrackFormat(it) }
            }.firstOrNull()?.let { format ->
                SelectedTrackEvidence(format.sampleMimeType, format.width, format.height,
                    format.colorInfo?.colorTransfer ?: -1)
            }
        val observed = MediaQuality.verified(selected(C.TRACK_TYPE_VIDEO), selected(C.TRACK_TYPE_AUDIO))
        if (observed.isKnown) latestMediaSpecCallback(observed)
    }
    LaunchedEffect(player, showVerification) {
        while (showVerification) {
            verificationTick++
            delay(1_000)
        }
    }
    // Closing a live channel drawer removes its focused node. Restore the
    // player anchor so the very next Left/OK opens the menu without a dead press.
    LaunchedEffect(player, focusRestoreEpoch) { initialControl.requestFocus() }
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
    fun requestBack(source: String) {
        android.util.Log.i("HerrinPlayerBack",
            "source=$source focus=${context.javaClass.simpleName} released=${player.isReleased}")
        onBack()
    }
    BackHandler { requestBack("dispatcher") }
    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode == KeyEvent.KEYCODE_BACK) {
            if (key.action == KeyEvent.ACTION_UP) requestBack("compose-preview")
            true
        } else if (isLive && key.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onLiveMenu()
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
                        if (event.action == KeyEvent.ACTION_UP) requestBack("native-player-view")
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
        if (isLive) {
            // A tiny focus anchor lets DPAD_LEFT/OK open the channel drawer while
            // the video remains edge-to-edge. No visible transport controls.
            Box(Modifier.size(1.dp).focusRequester(initialControl).focusable()
                .testTag("live-player-focus"))
            if (errorMessage != null) {
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp)
                    .background(Color(0xD9161B25), RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Text(errorMessage!!, color = Color(0xFFFFD0D0),
                        modifier = Modifier.testTag("player-error"))
                    Button(onClick = { retryPlayback() }, modifier = Modifier.testTag("player-retry")) {
                        Text("重试")
                    }
                }
            }
        } else Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = { requestBack("visible-button") }) {
                Text(if (isLive) "返回频道" else "返回详情")
            }
            Text(if (isLive) "仅播放你有权使用的直播频道"
                else "仅播放你有权使用的媒体 · 播放源按所选影片/线路确定", color = Color.White)
            Button(onClick = { showVerification = !showVerification },
                modifier = Modifier.testTag("player-verification-toggle")) {
                Text(if (showVerification) "收起实播状态" else "查看实播状态")
            }
            if (showVerification) {
                val _observeVerification = verificationTick + uiEpoch
                val counters = player.videoDecoderCounters
                Text(PlaybackAcceptance.summary(player.playbackState,
                    counters?.renderedOutputBufferCount ?: 0,
                    counters?.droppedBufferCount ?: 0,
                    player.videoSize.width, player.videoSize.height,
                    PlaybackTransport.options(player, C.TRACK_TYPE_AUDIO).size,
                    PlaybackTransport.options(player, C.TRACK_TYPE_TEXT).size,
                    errorMessage), color = Color(0xFFDBE8F9),
                    modifier = Modifier.testTag("player-verification-status"))
                Text("这是解码器出帧与丢帧计数，不代表电视面板刷新率或界面帧率。",
                    color = Color(0xFFADBBD0))
            }
            if (errorMessage != null) {
                Text(errorMessage!!, color = Color(0xFFFFD0D0), modifier = Modifier.testTag("player-error"))
                Button(onClick = { retryPlayback() },
                    modifier = Modifier.testTag("player-retry")) { Text("重试播放") }
            } else if (activeVariant > 0 && playbackState != Player.STATE_READY) {
                Text("正在尝试备用播放地址 ${activeVariant + 1} / ${media.alternateUris.size + 1}",
                    color = Color(0xFFCFD7E4), modifier = Modifier.testTag("player-fallback-status"))
            } else if (playbackState == Player.STATE_BUFFERING) {
                Text("正在缓冲授权媒体…", color = Color(0xFFCFD7E4),
                    modifier = Modifier.testTag("player-buffering"))
            } else if (playbackState == Player.STATE_ENDED) {
                Text("播放结束 · 可返回详情选择其他集数", color = Color(0xFFCFD7E4))
            }
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
        if (!isLive) Column(
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
                Button(onClick = {
                    player.setPlaybackSpeed(PlaybackTransport.nextSpeed(player.playbackParameters.speed))
                    uiEpoch++
                }, modifier = Modifier.testTag("transport-speed")) {
                    Text("倍速: ${player.playbackParameters.speed}×")
                }
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
