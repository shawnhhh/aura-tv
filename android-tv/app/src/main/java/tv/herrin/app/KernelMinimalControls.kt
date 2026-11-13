/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chrome for the single original session. No player/renderer is constructed here. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun KernelMinimalControls(
    session: HerrinPlayerSession, title: String, position: Long, duration: Long,
    initialFocus: FocusRequester, settingsFocus: FocusRequester,
    onBack: () -> Unit, onSeek: (Long) -> Unit, onInteraction: () -> Unit,
    onPanel: (Boolean) -> Unit, onLines: (() -> Unit)?, onEpisodes: (() -> Unit)?
) {
    require(session.whole)
    val player = session.player
    var panel by remember { mutableStateOf<String?>(null) }
    val audioFormat = player.currentTracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }
        .firstOrNull()
        val context = LocalContext.current
    var capabilities by remember { mutableStateOf<DeviceMediaCapabilitySnapshot?>(null) }
    LaunchedEffect(panel, audioFormat) {
        onPanel(panel != null)
        if (panel == "技术信息") capabilities = withContext(Dispatchers.IO) {
            val snapshot = DeviceMediaCapabilitiesAndroid.inspect(context)
            val mime = audioFormat?.sampleMimeType
            if (mime == null) snapshot else snapshot.copy(audioFormats = listOf(
                DeviceAudioCapability(mime, snapshot.decoderSupport(mime),
                    DeviceMediaCapabilitiesAndroid.directPlayback(mime, audioFormat.sampleRate, audioFormat.channelCount),
                    audioFormat.sampleRate, audioFormat.channelCount)))
        }
    }
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
            TextButton(onClick = onBack) { Text("‹", color = Color.White) }
            Text(title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { open("设置") }, modifier = Modifier.focusRequester(settingsFocus)
                .testTag("kernel-settings")) { Text("⚙", color = Color.White) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(position), color = Color.White)
                Text(clock(duration), color = Color.White)
            }
            LinearProgressIndicator(progress = {
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Color.White,
                trackColor = Color(0x66555555))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open("倍速") }, modifier = Modifier.weight(1f)) {
                    Text("${player.playbackParameters.speed}x", color = Color.White)
                }
                TextButton(onClick = { onSeek(-10_000) }) { Text("↶ 10", color = Color.White) }
                TextButton(onClick = {
                    if (player.playWhenReady) session.pause() else session.play()
                    onInteraction()
                }, modifier = Modifier.focusRequester(initialFocus).focusProperties { down = settingsFocus }.testTag("kernel-toggle")) {
                    Text(if (player.playWhenReady) "Ⅱ" else "▷", color = Color.White)
                }
                TextButton(onClick = { onSeek(10_000) }) { Text("10 ↷", color = Color.White) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { open("音轨") }) { Text("音轨", color = Color.White) }
                    TextButton(onClick = { open("字幕") }) { Text("字幕", color = Color.White) }
                }
            }
        }
    }
    panel?.let { name ->
        Dialog(onDismissRequest = { panel = null; onInteraction() }) {
            BackHandler { panel = null; onInteraction() }
            Surface(color = Color(0xF0111111), tonalElevation = 0.dp) {
                Column(Modifier.widthIn(min = 280.dp, max = 560.dp).heightIn(max = 420.dp).padding(24.dp)) {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    LazyColumn {
                        when (name) {
                            "设置" -> {
                                onLines?.let { action -> item { TextButton(onClick = { panel = null; action() }) { Text("线路") } } }
                                onEpisodes?.let { action -> item { TextButton(onClick = { panel = null; action() }) { Text("选集") } } }
                                item { TextButton(onClick = { open("技术信息") }) { Text("技术信息") } }
                            }
                            "倍速" -> items(PlaybackTransport.speeds) { speed ->
                                TextButton(onClick = { session.manager.setSpeed(speed); panel = null; onInteraction() }) { Text("${speed}x") }
                            }
                            "音轨", "字幕" -> {
                                val type = if (name == "音轨") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                                if (type == C.TRACK_TYPE_TEXT) item {
                                    TextButton(onClick = { session.manager.selectTrack(type, -1, -1); panel = null; onInteraction() }) { Text("关闭字幕") }
                                }
                                val options = PlaybackTransport.options(player, type)
                                if (options.isEmpty()) item { Text("暂无可用轨道", color = Color.White) }
                                items(options) { option ->
                                    TextButton(onClick = {
                                        session.manager.selectTrack(type, option.groupIndex, option.trackIndex)
                                        panel = null; onInteraction()
                                    }) { Text((if (option.selected) "✓ " else "") + option.label) }
                                }
                            }
                            "技术信息" -> item {
                                val format = player.videoFormat
                                Text("视频轨：${format?.sampleMimeType ?: "未知"}\n" +
                                    "${player.videoSize.width} × ${player.videoSize.height} / ${format?.frameRate ?: -1} fps\n" +
                                    "显示设备声明 HDR：${capabilities?.hdrFormats ?: "查询中"}\n" +
                                    "音轨：${PlaybackTransport.currentLabel(player, C.TRACK_TYPE_AUDIO)}\n" +
                                    "音轨编码：${audioFormat?.sampleMimeType ?: "未知"} / ${audioFormat?.sampleRate ?: -1} Hz / ${audioFormat?.channelCount ?: -1} ch\n" +
                                    "平台输出候选：${audioFormat?.let { capabilities?.audioDelivery(it.sampleMimeType.orEmpty(), it.sampleRate, it.channelCount) } ?: "未知"}\n" +
                                    "DV 5/7/8 硬解声明：${DolbyVisionProfile.entries.joinToString { capabilities?.dolbyVisionAdmission(it)?.hardwareDecoder?.name ?: "未知" }}\n" +
                                    "Profile 7 完整双层：未验证\n" +
                                    "实际 HDR / 音频输出路径：尚无已验证运行时证据", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
