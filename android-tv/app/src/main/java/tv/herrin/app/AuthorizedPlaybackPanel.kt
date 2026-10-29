package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
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
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    onLineSelection: (() -> Unit)? = null,
    onEpisodeSelection: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    // A media replacement owns a fresh composition, including callbacks,
    // effects, focus nodes and native surfaces. No outgoing resource can be
    // retained in a slot that now represents another episode or line.
    key(media) {
        AuthorizedPlaybackSession(media, onPlayer, startPositionMs, onPlaybackProgress,
            onMediaSpecDetected, isLive, onLiveMenu, focusRestoreEpoch,
            onLineSelection, onEpisodeSelection, onBack)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun AuthorizedPlaybackSession(
    media: PlaybackMedia,
    onPlayer: (ExoPlayer) -> Unit,
    startPositionMs: Long,
    onPlaybackProgress: (Long, Long, Boolean) -> Unit,
    onMediaSpecDetected: (VerifiedStreamSpec) -> Unit,
    isLive: Boolean,
    onLiveMenu: () -> Unit,
    focusRestoreEpoch: Int,
    onLineSelection: (() -> Unit)?,
    onEpisodeSelection: (() -> Unit)?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val danmaku = rememberDanmakuSettings()
    var errorMessage by remember(media) { mutableStateOf<String?>(null) }
    var playbackState by remember(media) { mutableIntStateOf(Player.STATE_IDLE) }
    var uiEpoch by remember(media) { mutableIntStateOf(0) }
    var activeVariant by remember(media) { mutableIntStateOf(0) }
    var shownPosition by remember(media.uri, media.headers, media.subtitles) { mutableLongStateOf(0L) }
    var shownDuration by remember(media.uri, media.headers, media.subtitles) { mutableLongStateOf(0L) }
    var showVerification by remember { mutableStateOf(false) }
    var verificationTick by remember { mutableIntStateOf(0) }
    val initialControl = remember { FocusRequester() }
    val selectionControl = remember { FocusRequester() }
    val playerRootFocus = remember { FocusRequester() }
    var controlsVisible by remember(media) { mutableStateOf(true) }
    var transportFocused by remember { mutableStateOf(false) }
    var playerRootFocused by remember { mutableStateOf(false) }
    var controlsInteractionEpoch by remember { mutableIntStateOf(0) }
    var focusControlsEpoch by remember { mutableIntStateOf(0) }
    // Keep updates within one media session, while retaining the old session's
    // callbacks until its DisposableEffect has persisted and released its player.
    // A single shared State would route old onDispose progress into the new episode.
    val latestProgressCallback by rememberUpdatedState(onPlaybackProgress)
    val latestMediaSpecCallback by rememberUpdatedState(onMediaSpecDetected)
    val retries = remember(media) {
        PlaybackFormatRetry.Session(media.uri, media.alternateUris, media.declaredMime)
    }
    val candidates = retries.candidates
    val journey = remember(media) { PlaybackJourneyTrace() }
    fun itemFor(uri: String, candidateIndex: Int, mimeOverride: String? = null): MediaItem = MediaItem.Builder().setUri(uri).apply {
        if (media.subtitles.isNotEmpty()) {
            setSubtitleConfigurations(media.subtitles.mapIndexed { index, subtitle ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage(subtitle.language)
                    .setLabel(subtitle.title)
                    .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
        }
        (mimeOverride ?: PlaybackFormatRetry.candidateMime(uri, candidateIndex, media.declaredMime))?.let(::setMimeType)
    }.build()
    val player = remember(media, context, lifecycle) {
        journey.begin(0, candidates.size, android.os.SystemClock.elapsedRealtime())
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(media.allowHttpRedirects)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(media.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        // If a vendor MediaCodec fails initialization, try another compatible
        // system decoder before declaring the selected source unplayable.
        // Retain Android's hardware-first pipeline and HDR output behavior.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    uiEpoch++
                }
                override fun onTracksChanged(tracks: Tracks) {
                    journey.onTracks(
                        tracks.groups.any { group -> group.type == C.TRACK_TYPE_VIDEO &&
                            (0 until group.length).any { group.isTrackSelected(it) } },
                        tracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
                            (0 until group.length).any { group.isTrackSelected(it) } })
                    uiEpoch++
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { uiEpoch++ }
                override fun onRenderedFirstFrame() { uiEpoch++ }
                override fun onIsPlayingChanged(isPlaying: Boolean) { uiEpoch++ }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    journey.onPlayWhenReady(playWhenReady, android.os.SystemClock.elapsedRealtime())
                    uiEpoch++
                }
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                    journey.onState(state, android.os.SystemClock.elapsedRealtime())
                    uiEpoch++
                }
                override fun onPlayerError(error: PlaybackException) {
                    val httpStatus = PlaybackFailureHints.safeHttpStatus(
                        PlaybackFailureHints.causes(error)
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode)
                    journey.onFailure(PlaybackFailureHints.category(error.errorCodeName, httpStatus))
                    val next = retries.next(error.errorCode, httpStatus)
                    if (next != null) {
                        // Retry only the exact provider-supplied URLs. The session owns
                        // both the per-address MIME and a finite monotonic retry budget.
                        val recoveryPositionMs = currentPosition.coerceAtLeast(0L)
                        activeVariant = next.candidateIndex
                        journey.begin(next.candidateIndex, candidates.size,
                            android.os.SystemClock.elapsedRealtime(),
                            videoDecoderCounters?.renderedOutputBufferCount ?: 0)
                        errorMessage = null
                        setMediaItem(itemFor(next.uri, next.candidateIndex, next.mime))
                        if (!isLive && recoveryPositionMs > 0L) seekTo(recoveryPositionMs)
                        prepare()
                        play()
                    } else {
                        errorMessage = PlaybackFailureHints.hint(error.errorCodeName, httpStatus) +
                            "（${PlaybackFailureHints.safeCode(error.errorCodeName)}" +
                            "${httpStatus?.let { ", HTTP $it" }.orEmpty()}" +
                            "; 地址 ${activeVariant + 1}/${candidates.size}）"
                    }
                }
            })
        }
    }
    fun reportProgress() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        latestProgressCallback(player.currentPosition.coerceAtLeast(0L), duration,
            player.playbackState == Player.STATE_ENDED)
    }
    fun retryPlayback() {
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val first = retries.restart()
        journey.clearPreviousFailure()
        journey.begin(first.candidateIndex, candidates.size,
            android.os.SystemClock.elapsedRealtime(),
            player.videoDecoderCounters?.renderedOutputBufferCount ?: 0)
        activeVariant = first.candidateIndex
        errorMessage = null
        player.setMediaItem(itemFor(first.uri, first.candidateIndex, first.mime))
        if (!isLive && resumePosition > 0L) player.seekTo(resumePosition)
        player.prepare()
        player.play()
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(500)
            journey.onFrames(player.videoDecoderCounters?.renderedOutputBufferCount ?: 0,
                android.os.SystemClock.elapsedRealtime())
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
    LaunchedEffect(player, focusRestoreEpoch) {
        if (isLive) initialControl.requestFocus() else playerRootFocus.requestFocus()
    }
    LaunchedEffect(focusControlsEpoch) {
        if (!isLive && focusControlsEpoch > 0) {
            // A remote press during initial buffering can precede composition of
            // the controls. Wait for real video rather than focusing an unattached node.
            while ((player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) == 0 &&
                player.playerError == null) delay(50)
            if (player.playerError == null && controlsVisible) initialControl.requestFocus()
        }
    }
    LaunchedEffect(player, controlsVisible, playbackState, controlsInteractionEpoch, uiEpoch, showVerification) {
        // A focused button must not pin the overlay forever. Any remote key restarts
        // this timer; while paused the controls remain available for resuming.
        if (!isLive && controlsVisible && player.isPlaying &&
            playbackState == Player.STATE_READY && !showVerification && errorMessage == null) {
            delay(8_000)
            controlsVisible = false
            playerRootFocus.requestFocus()
        }
    }
    DisposableEffect(player, lifecycle) {
        // Compose/Activity transitions can briefly overlap outgoing and incoming
        // players. Media3's default empty ID is process-global and rejects that
        // valid handoff; each player owns a distinct, credential-free session ID.
        val session = MediaSession.Builder(context, player)
            .setId("herrin-${java.util.UUID.randomUUID()}").build()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { reportProgress(); player.pause() }
        }
        lifecycle.addObserver(observer)
        // Start only after this composition has committed and the outgoing
        // effect has released its session. Starting inside remember overlaps
        // prepare/play with the previous player's teardown during inline swaps.
        player.setMediaItem(itemFor(candidates.first(), 0))
        if (startPositionMs > 0L)
            player.seekTo(startPositionMs.coerceAtMost(12L * 60L * 60_000L))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            reportProgress()
            lifecycle.removeObserver(observer)
            session.release()
            player.stop()
            player.release()
        }
    }
    // Publish the player from the committed composition, after remember/dispose
    // callbacks. A transition must never leave an observer holding a player
    // created by an outgoing or abandoned composition.
    SideEffect { onPlayer(player) }
    fun requestBack(source: String) {
        android.util.Log.i("HerrinPlayerBack",
            "source=$source focus=${context.javaClass.simpleName} released=${player.isReleased}")
        onBack()
    }
    BackHandler { requestBack("dispatcher") }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(playerRootFocus)
        .onFocusChanged { playerRootFocused = it.isFocused }.focusable()
        .onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action == KeyEvent.ACTION_DOWN) controlsInteractionEpoch++
        if (key.keyCode == KeyEvent.KEYCODE_BACK) {
            if (key.action == KeyEvent.ACTION_UP) requestBack("compose-preview")
            true
        } else if (!isLive && transportFocused &&
            key.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                transportFocused = false
                controlsVisible = false
                playerRootFocus.requestFocus()
            }
            true
        } else if (!isLive && playerRootFocused &&
            key.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                PlaybackTransport.seekBy(player,
                    if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                // Give visual confirmation of the seek and then auto-hide.
                controlsVisible = true
                controlsInteractionEpoch++
            }
            true
        } else if (!isLive && playerRootFocused &&
            key.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                controlsVisible = true
                controlsInteractionEpoch++
                focusControlsEpoch++
            }
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
        // A PlayerView owns its native video surface and key handlers for one
        // player. Replacing media must replace that surface too: the outgoing
        // decoder can still be detaching while Compose installs the new player.
        key(player) {
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
                setPadding(0, 0, 0, 0)
            } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        }
        if (!isLive) Media3DanmakuOverlay(player, media.uri, media.danmakuSources,
            danmaku.value, Modifier.fillMaxSize())
        val hasDecodedVideo = (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
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
        } else if ((controlsVisible && hasDecodedVideo) || showVerification || errorMessage != null)
            Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = { requestBack("visible-button") }) {
                Text(if (isLive) "返回频道" else "返回详情")
            }
            // Movie metadata/technical resolver messages are not shown over the film.
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
                Text(journey.summary(android.os.SystemClock.elapsedRealtime()),
                    color = Color(0xFFDCE7FC),
                    modifier = Modifier.testTag("player-journey-status"))
                Text("阶段状态仅反映 Media3 的运行证据；不等同于 TCL 面板 HDR 或杜比音效实际输出。",
                    color = Color(0xFFADBBD0))
                Text("这是解码器出帧与丢帧计数，不代表电视面板刷新率或界面帧率。",
                    color = Color(0xFFADBBD0))
            }
            if (errorMessage != null) {
                Text(errorMessage!!, color = Color(0xFFFFD0D0), modifier = Modifier.testTag("player-error"))
                Button(onClick = { retryPlayback() },
                    modifier = Modifier.testTag("player-retry")) { Text("重试播放") }
            } else if (activeVariant > 0 && playbackState != Player.STATE_READY) {
                Text("正在尝试备用播放地址 ${activeVariant + 1} / ${candidates.size}",
                    color = Color(0xFFCFD7E4), modifier = Modifier.testTag("player-fallback-status"))

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
        if (!isLive && controlsVisible && hasDecodedVideo) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.86f).padding(bottom = 36.dp)
                .onFocusChanged { transportFocused = it.hasFocus }
                .background(Color(0xE9151820), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(elapsed(shownPosition), color = Color.White, modifier = Modifier.testTag("player-position-label"))
                val format = player.videoFormat
                Text("画质：" + PlayerQualityLabel.fromDecoded(
                    player.videoSize.width, player.videoSize.height,
                    format?.frameRate ?: -1f, format?.sampleMimeType,
                    format?.colorInfo?.colorTransfer ?: -1,
                    player.videoDecoderCounters?.renderedOutputBufferCount ?: 0),
                    color = Color(0xFFD3E6F7), modifier = Modifier.testTag("player-quality-label"))
                Text(elapsed(shownDuration), color = Color(0xFFD2D5DE), modifier = Modifier.testTag("player-duration-label"))
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF434857))) {
                Box(Modifier.fillMaxWidth(percent).fillMaxHeight().background(Color.White))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlaybackTransport.seekBy(player, -10_000L) },
                    modifier = Modifier.testTag("transport-rewind")) { Text("−10s") }
                Button(onClick = { if (player.playWhenReady) player.pause() else player.play() },
                    modifier = Modifier.focusRequester(initialControl).focusProperties {
                        if (onLineSelection != null || onEpisodeSelection != null) down = selectionControl
                    }.testTag("transport-toggle")) {
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
            if (onLineSelection != null || onEpisodeSelection != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    onLineSelection?.let { select ->
                        Button(onClick = select, modifier = Modifier.focusRequester(selectionControl).testTag("transport-lines")) { Text("线路") }
                    }
                    onEpisodeSelection?.let { select ->
                        Button(onClick = select, modifier = Modifier.then(
                            if (onLineSelection == null) Modifier.focusRequester(selectionControl) else Modifier
                        ).testTag("transport-episodes")) { Text("选集") }
                    }
                }
            }
            DanmakuControls(danmaku.value, { danmaku.value = it })
        }
    }
}
