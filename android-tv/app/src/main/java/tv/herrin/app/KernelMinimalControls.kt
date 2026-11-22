/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.C
import androidx.media3.common.Player

/** Chrome for the single original session. No player/renderer is constructed here. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun KernelMinimalControls(
    session: HerrinPlayerSession, title: String, position: Long, duration: Long,
    initialFocus: FocusRequester, speedFocus: FocusRequester,
    onSeek: (Long) -> Unit, onInteraction: () -> Unit,
    onPanel: (Boolean) -> Unit, onEpisodes: (() -> Unit)?,
    onHoldSeek: (Int, KeyEvent) -> Boolean, resetHoldSeek: () -> Unit, seekAcceleration: Int, specsReady: Boolean
) {
    val player = session.player
    // Media3 is not Compose snapshot state: pause/resume can change without a position tick.
    // Keep the transport icon and accessible description tied to the actual player events.
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                playWhenReady = ready
            }
        }
        player.addListener(listener)
        playWhenReady = player.playWhenReady
        onDispose { player.removeListener(listener) }
    }
    fun Modifier.seekButton(direction: Int) = onFocusChanged { if (!it.isFocused) resetHoldSeek() }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER))
                onHoldSeek(direction, key) else false
        }
    var panel by remember { mutableStateOf<String?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf<String?>(null) }
    val episodesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    val speedOffset = with(LocalDensity.current) { IntOffset(56.dp.roundToPx(), -124.dp.roundToPx()) }
    val panelWidth = (LocalConfiguration.current.screenWidthDp * .26f).dp
    val userReduced = Preferences(androidx.compose.ui.platform.LocalContext.current).reducedMotion
    val reduced = userReduced || android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()
    fun close() {
        closing = panel
        visibility.targetState = false
        onInteraction()
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, closing) {
        val previous = closing
        if (previous != null && visibility.isIdle && !visibility.currentState) {
            panel = null
            closing = null
            onPanel(false)
            withFrameNanos { }
            (when (previous) { "选集" -> episodesFocus; "音轨" -> audioFocus; "字幕" -> subtitleFocus; else -> speedFocus }).requestFocus()
        }
    }
    LaunchedEffect(panel) {
        onPanel(panel != null)
    }
    DisposableEffect(Unit) { onDispose { onPanel(false) } }
    fun open(name: String) { closing = null; panel = name; visibility.targetState = true; onPanel(true); onInteraction() }
    fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x66000000)),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 22.sp, modifier = Modifier.widthIn(max = 520.dp).testTag("player-title"), maxLines = 1)
            val format = player.videoFormat
            if (specsReady && format != null && format.width > 0 && format.height > 0) {
                Text("  " + PlayerQualityLabel.fromSize(format.width, format.height), color = Color.White,
                    modifier = Modifier.testTag("player-decoded-quality"))
                val range = when {
                    format?.sampleMimeType == "video/dolby-vision" -> "Dolby Vision"
                    format?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                        format?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HDR"
                    else -> null
                }
                range?.let { Text("  $it", color = Color.White) }
            }

        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(position), color = Color.White, modifier = Modifier.testTag("player-position-label"))
                Text(clock(duration), color = Color.White, modifier = Modifier.testTag("player-duration-label"))
            }
            if (seekAcceleration > 1) Text("${seekAcceleration}×  ${clock(position)}", color = Color.White,
                modifier = Modifier.testTag("seek-acceleration"))
            LinearProgressIndicator(progress = {
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Color.White,
                trackColor = Color(0x66555555))
            PlayerControlBar(playWhenReady, player.playbackParameters.speed, session.whole,
                initialFocus, speedFocus, episodesFocus, audioFocus, subtitleFocus,
                Modifier.seekButton(-1), Modifier.seekButton(1),
                onEpisodes = { onInteraction(); onEpisodes?.invoke() ?: open("选集") },
                onSpeed = { open("倍速") }, onRewind = { onSeek(-10_000) },
                onToggle = {
                    if (player.playWhenReady) session.pause() else session.play()
                    playWhenReady = player.playWhenReady
                    onInteraction()
                },
                onForward = { onSeek(10_000) }, onAudio = { open("音轨") }, onSubtitles = { open("字幕") })
        }
    }
    panel?.let { name ->
        Popup(alignment = when (name) { "倍速" -> Alignment.BottomStart; "选集" -> Alignment.CenterStart; else -> Alignment.CenterEnd },
            offset = if (name == "倍速") speedOffset else IntOffset.Zero,
            onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
            BackHandler { close() }
            Box(Modifier.testTag("player-menu-popup")) {
            AnimatedVisibility(visibility, enter = slideInHorizontally(tween(if (reduced) 0 else 180)) { if (name == "倍速") 0 else if (name == "选集") -it else it },
                exit = slideOutHorizontally(tween(if (reduced) 0 else 180)) { if (name == "倍速") 0 else if (name == "选集") -it else it }) {
            Surface(color = Color(0xD9111111), tonalElevation = 0.dp) {
                Column(Modifier.width(if (name == "倍速") 160.dp else panelWidth).heightIn(max = 420.dp).padding(24.dp)) {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    val type = if (name == "音轨") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                    PlayerControlMenu(name, player.playbackParameters.speed,
                        if (name in setOf("音轨", "字幕")) PlaybackTransport.options(player, type) else emptyList(),
                        visibility.targetState && closing == null,
                        onSpeed = { session.manager.setSpeed(it); close() },
                        onTrack = { group, track -> session.manager.selectTrack(type, group, track); close() })
                }
            }
            }
            }
        }
    }
}
