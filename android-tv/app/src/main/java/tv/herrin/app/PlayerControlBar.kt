@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package tv.herrin.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val PlayerControlSymbol = SemanticsPropertyKey<String>("PlayerControlSymbol")
internal var SemanticsPropertyReceiver.playerControlSymbol by PlayerControlSymbol

internal enum class PlayerSymbol(val key: String) {
    Episodes("list-video"), Rewind("rotate-ccw"), Play("play"), Pause("pause"),
    Forward("rotate-cw"), Audio("audio-lines"), Subtitles("subtitles")
}

/** Original stroke artwork; resolution independent, with no font-glyph fallback. */
@Composable
internal fun PlayerControlIcon(symbol: PlayerSymbol, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { playerControlSymbol = symbol.key }) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, endX: Float, endY: Float) =
                drawLine(Color.White, Offset(x, y), Offset(endX, endY), 1.8f, StrokeCap.Round)
            when (symbol) {
                PlayerSymbol.Play -> drawPath(Path().apply {
                    moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close()
                }, Color.White)
                PlayerSymbol.Pause -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
                PlayerSymbol.Rewind, PlayerSymbol.Forward -> {
                    val backwards = symbol == PlayerSymbol.Rewind
                    drawArc(Color.White, if (backwards) -90f else -90f,
                        if (backwards) -285f else 285f, false,
                        Offset(3f, 3f), Size(18f, 18f), style = stroke)
                    if (backwards) { line(12f, 3f, 8f, 3f); line(8f, 3f, 8f, 7f) }
                    else { line(12f, 3f, 16f, 3f); line(16f, 3f, 16f, 7f) }
                }
                PlayerSymbol.Audio -> listOf(2f to 4f, 6f to 12f, 10f to 18f,
                    14f to 8f, 18f to 14f, 22f to 4f).forEach { (x, height) ->
                    line(x, 12f - height / 2, x, 12f + height / 2)
                }
                PlayerSymbol.Subtitles -> {
                    drawRoundRect(Color.White, Offset(2f, 4f), Size(20f, 16f), CornerRadius(2f), style = stroke)
                    line(6f, 10f, 9f, 10f); line(12f, 10f, 18f, 10f)
                    line(6f, 15f, 12f, 15f); line(15f, 15f, 18f, 15f)
                }
                PlayerSymbol.Episodes -> {
                    line(3f, 5f, 21f, 5f); line(3f, 10f, 10f, 10f)
                    line(3f, 15f, 10f, 15f); line(3f, 20f, 10f, 20f)
                    drawPath(Path().apply { moveTo(15f, 10f); lineTo(22f, 15f); lineTo(15f, 20f); close() },
                        Color.White, style = stroke)
                }
            }
        }
    }
}

/** Fixed layout slots: focus scaling changes drawing, never neighbours' bounds. */
@Composable
private fun PlayerControlButton(label: String, tag: String, slot: androidx.compose.ui.unit.Dp,
    focusSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
        TextButton(onClick, modifier.size(focusSize).onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = if (focused) 1.04f else 1f; scaleY = scaleX }
            .semantics { contentDescription = label }.testTag(tag),
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(6.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            border = BorderStroke(1.5.dp, if (focused) Color.White else Color.Transparent)) { content() }
    }
}

/** Seven controls over the existing session. No media or source selection logic here. */
@Composable
internal fun PlayerControlBar(playing: Boolean, speed: Float, whole: Boolean,
    initialFocus: FocusRequester, speedFocus: FocusRequester, episodesFocus: FocusRequester,
    audioFocus: FocusRequester, subtitleFocus: FocusRequester,
    rewindModifier: Modifier, forwardModifier: Modifier,
    onEpisodes: () -> Unit, onSpeed: () -> Unit, onRewind: () -> Unit,
    onToggle: () -> Unit, onForward: () -> Unit, onAudio: () -> Unit, onSubtitles: () -> Unit) {
    val rewindFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("player-control-bar")) {
        val factor = (maxWidth.value / 680f).coerceAtMost(1f)
        // TCL real-device feedback: retain couch-readable hierarchy without oversized controls.
        val side = 54.dp * factor
        val seek = 64.dp * factor
        val primary = 82.dp * factor
        val gap = 10.dp * factor
        fun Modifier.neighbours(leftTarget: FocusRequester, rightTarget: FocusRequester) = focusProperties {
            left = leftTarget; right = rightTarget; up = speedFocus
        }
        @Composable fun icon(symbol: PlayerSymbol, extent: Float) {
            Box(contentAlignment = Alignment.Center) {
                PlayerControlIcon(symbol, Modifier.size((extent * factor).dp))
                if (symbol == PlayerSymbol.Rewind || symbol == PlayerSymbol.Forward)
                    Text("10", color = Color.White, fontSize = (11 * factor).sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).testTag("player-controls-left"),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                PlayerControlButton("选集", "transport-episodes", side, side,
                    Modifier.focusRequester(episodesFocus).neighbours(FocusRequester.Cancel, speedFocus), onEpisodes) { icon(PlayerSymbol.Episodes, 25f) }
                PlayerControlButton("倍速", "transport-speed", side, side,
                    Modifier.focusRequester(speedFocus).focusProperties { left = episodesFocus; right = rewindFocus; up = FocusRequester.Cancel }, onSpeed) {
                    Text("${speed}×", color = Color.White, fontSize = (15 * factor).sp, maxLines = 1)
                }
            }
            Row(Modifier.testTag("player-controls-center"), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically) {
                PlayerControlButton("快退10秒", "transport-rewind", seek, side,
                    Modifier.focusRequester(rewindFocus).neighbours(speedFocus, initialFocus).then(rewindModifier), onRewind) { icon(PlayerSymbol.Rewind, 27f) }
                PlayerControlButton(if (playing) "暂停" else "播放", if (whole) "kernel-toggle" else "transport-toggle", primary, side,
                    Modifier.focusRequester(initialFocus).neighbours(rewindFocus, forwardFocus), onToggle) {
                    icon(if (playing) PlayerSymbol.Pause else PlayerSymbol.Play, 28f)
                }
                PlayerControlButton("快进10秒", "transport-forward", seek, side,
                    Modifier.focusRequester(forwardFocus).neighbours(initialFocus, audioFocus).then(forwardModifier), onForward) { icon(PlayerSymbol.Forward, 27f) }
            }
            Row(Modifier.weight(1f).testTag("player-controls-right"), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                PlayerControlButton("音轨", "transport-audio", side,
                    Modifier.focusRequester(audioFocus).neighbours(forwardFocus, subtitleFocus), onAudio) { icon(PlayerSymbol.Audio, 25f) }
                PlayerControlButton("字幕", "transport-subtitle", side,
                    Modifier.focusRequester(subtitleFocus).neighbours(audioFocus, FocusRequester.Cancel), onSubtitles) { icon(PlayerSymbol.Subtitles, 25f) }
            }
        }
    }
}
