/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.C

/** Chrome for the single original session. No player/renderer is constructed here. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun KernelMinimalControls(
    session: HerrinPlayerSession, title: String, position: Long, duration: Long,
    initialFocus: FocusRequester, settingsFocus: FocusRequester,
    onBack: () -> Unit, onSeek: (Long) -> Unit, onInteraction: () -> Unit,
    onPanel: (Boolean) -> Unit, onLines: (() -> Unit)?, onEpisodes: (() -> Unit)?,
    onHoldSeek: (Int, KeyEvent) -> Boolean, resetHoldSeek: () -> Unit, seekAcceleration: Int, specsReady: Boolean
) {
    val player = session.player
    fun Modifier.seekButton(direction: Int) = onFocusChanged { if (!it.isFocused) resetHoldSeek() }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER))
                onHoldSeek(direction, key) else false
        }
    var panel by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    val userReduced = Preferences(androidx.compose.ui.platform.LocalContext.current).reducedMotion
    val reduced = userReduced || android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()
    fun close() {
        val previous = panel
        visible = false
        jobs.launch {
            if (!reduced) delay(180)
            panel = null
            onInteraction()
            withFrameNanos { }
            runCatching { (when (previous) { "音轨" -> audioFocus; "字幕" -> subtitleFocus; else -> settingsFocus }).requestFocus() }
        }
    }
    LaunchedEffect(panel) { onPanel(panel != null); visible = panel != null }
    DisposableEffect(Unit) { onDispose { onPanel(false) } }
    fun open(name: String) { panel = name; onInteraction() }
    fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x66000000)),
            verticalAlignment = Alignment.CenterVertically) {
            PlayerTextButton(onClick = onBack, modifier = Modifier.testTag("player-back")) { Text("‹", color = Color.White) }
            Text(title, color = Color.White, modifier = Modifier.widthIn(max = 520.dp), maxLines = 1)
            val format = player.videoFormat
            if (specsReady && player.videoSize.width > 0 && player.videoSize.height > 0 &&
                (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0) {
                Text("  " + PlayerQualityLabel.fromSize(player.videoSize.width, player.videoSize.height), color = Color.White)
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
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.CenterStart)) {
                    onEpisodes?.let { action ->
                        PlayerTextButton(onClick = { onInteraction(); action() }, modifier = Modifier.semantics { contentDescription = "选集" }.testTag("transport-episodes")) { Text("▦", color = Color.White) }
                    }
                    PlayerTextButton(onClick = { open("倍速") }, modifier = Modifier.focusRequester(settingsFocus).testTag("transport-speed")) {
                        Text("${player.playbackParameters.speed}×", color = Color.White)
                    }
                }
                Row(Modifier.align(Alignment.Center)) {
                PlayerTextButton(onClick = { onSeek(-10_000) }, modifier = Modifier.testTag("transport-rewind").seekButton(-1)) { Text("↶ 10", color = Color.White) }
                PlayerTextButton(onClick = {
                    if (player.playWhenReady) session.pause() else session.play()
                    onInteraction()
                }, modifier = Modifier.focusRequester(initialFocus).focusProperties { up = settingsFocus }.testTag(if (session.whole) "kernel-toggle" else "transport-toggle")) {
                    Text(if (player.playWhenReady) "Ⅱ" else "▷", color = Color.White)
                }
                PlayerTextButton(onClick = { onSeek(10_000) }, modifier = Modifier.testTag("transport-forward").seekButton(1)) { Text("10 ↷", color = Color.White) }
                }
                Row(Modifier.align(Alignment.CenterEnd)) {
                    PlayerTextButton(onClick = { open("音轨") }, modifier = Modifier.focusRequester(audioFocus).semantics { contentDescription = "音轨" }.testTag("transport-audio")) { Text("♫", color = Color.White) }
                    PlayerTextButton(onClick = { open("字幕") }, modifier = Modifier.focusRequester(subtitleFocus).semantics { contentDescription = "字幕" }.testTag("transport-subtitle")) { Text("▤", color = Color.White) }
                }
            }
        }
    }
    panel?.let { name ->
        Popup(alignment = if (name == "倍速") Alignment.BottomStart else Alignment.CenterEnd,
            onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
            BackHandler { close() }
            AnimatedVisibility(visible, enter = slideInHorizontally(tween(if (reduced) 0 else 180)) { if (name == "倍速") 0 else it },
                exit = slideOutHorizontally(tween(if (reduced) 0 else 180)) { if (name == "倍速") 0 else it }) {
            Surface(color = Color(0xD9111111), tonalElevation = 0.dp) {
                Column(Modifier.width(if (name == "倍速") 160.dp else 300.dp).heightIn(max = 420.dp).padding(24.dp)) {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    LazyColumn {
                        when (name) {
                            "倍速" -> items(PlaybackTransport.speeds.reversed()) { speed ->
                                PlayerTextButton(onClick = { session.manager.setSpeed(speed); close() }) { Text((if (kotlin.math.abs(player.playbackParameters.speed - speed) < 0.01f) "✓ " else "") + "${speed}×") }
                            }
                            "音轨", "字幕" -> {
                                val type = if (name == "音轨") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                                if (type == C.TRACK_TYPE_TEXT) item {
                                    PlayerTextButton(onClick = { session.manager.selectTrack(type, -1, -1); close() }) { Text("关闭字幕") }
                                }
                                val options = PlaybackTransport.options(player, type)
                                if (options.isEmpty()) item { Text("暂无可用轨道", color = Color.White) }
                                items(options) { option ->
                                    PlayerTextButton(onClick = {
                                        session.manager.selectTrack(type, option.groupIndex, option.trackIndex)
                                        close()
                                    }) { Text((if (option.selected) "✓ " else "") + option.label) }
                                }
                            }

                        }
                    }
                }
            }
            }
        }
    }
}
