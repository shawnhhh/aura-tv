package tv.herrin.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Styling persists; network enablement always starts OFF on a fresh application/player session. */
@Composable
fun rememberDanmakuSettings(): MutableState<DanmakuSettings> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("herrin_danmaku", 0) }
    val state = remember(prefs) { mutableStateOf(DanmakuSettings(
        opacity = prefs.getFloat("opacity", .75f), speed = prefs.getFloat("speed", 1f),
        fontSize = prefs.getFloat("fontSize", 24f)).normalized()) }
    LaunchedEffect(state.value) {
        val value = state.value.normalized()
        prefs.edit().putFloat("opacity", value.opacity).putFloat("speed", value.speed)
            .putFloat("fontSize", value.fontSize).apply()
    }
    return state
}

/** TV buttons use normal D-pad focus. The parent can place this row in its control sheet. */
@Composable
fun DanmakuControls(settings: DanmakuSettings, onChange: (DanmakuSettings) -> Unit,
                    modifier: Modifier = Modifier) {
    val value = settings.normalized()
    Row(modifier.testTag("danmaku-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onChange(value.copy(enabled = !value.enabled)) },
            modifier = Modifier.testTag("danmaku-toggle")) {
            Text(if (value.enabled) "弹幕：开" else "弹幕：关")
        }
        Button(onClick = { onChange(value.copy(opacity = when {
            value.opacity < .5f -> .5f; value.opacity < .75f -> .75f
            value.opacity < 1f -> 1f; else -> .25f })) },
            modifier = Modifier.testTag("danmaku-opacity")) { Text("透明度 ${(value.opacity * 100).toInt()}%") }
        Button(onClick = { onChange(value.copy(speed = when {
            value.speed < 1f -> 1f; value.speed < 1.5f -> 1.5f
            value.speed < 2f -> 2f; else -> .5f })) },
            modifier = Modifier.testTag("danmaku-speed")) { Text("速度 ${value.speed}×") }
        Button(onClick = { onChange(value.copy(fontSize = when {
            value.fontSize < 24f -> 24f; value.fontSize < 32f -> 32f
            value.fontSize < 40f -> 40f; else -> 16f })) },
            modifier = Modifier.testTag("danmaku-size")) { Text("字号 ${value.fontSize.toInt()}") }
    }
}

/** Place above PlayerView and below controls. Reads Media3 position on the UI thread.
 * Changing mediaKey MUST include film + episode + line (or media identity), which drops old data.
 * Optional sources are fetched only after enabled=true; failure produces an empty overlay.
 * No timer extrapolation: video pause, buffering, seek and speed changes all share the video clock.
 */
@Composable
fun Media3DanmakuOverlay(player: Player, mediaKey: String, sources: List<DanmakuSource>,
                         settings: DanmakuSettings, modifier: Modifier = Modifier,
                         repository: DanmakuRepository = remember { DanmakuRepository() }) {
    var comments by remember(mediaKey, sources, settings.enabled) {
        mutableStateOf<List<DanmakuComment>>(emptyList())
    }
    var position by remember(player, mediaKey) { mutableLongStateOf(0) }
    LaunchedEffect(mediaKey, sources, settings.enabled, repository) {
        comments = emptyList()
        val source = sources.firstOrNull()
        if (settings.enabled && source != null) {
            comments = withContext(Dispatchers.IO) { repository.load(source, explicitlyEnabled = true) }
        }
    }
    LaunchedEffect(player, mediaKey, settings.enabled) {
        while (settings.enabled) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(33)
        }
    }
    if (!settings.enabled || comments.isEmpty()) return
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val normalized = settings.normalized()
    val visible = DanmakuTimeline.visible(comments, position, normalized)
    Canvas(modifier.testTag("danmaku-overlay")) {
        val lineHeight = normalized.fontSize.dp.toPx() * 1.5f
        visible.forEach { entry ->
            paint.textSize = (normalized.fontSize * (entry.comment.size / 25f).coerceIn(.75f, 1.5f)).dp.toPx()
            paint.color = (0xff000000.toInt() or entry.comment.color)
            paint.alpha = (normalized.opacity * 255).toInt()
            paint.setShadowLayer(3.dp.toPx(), 1.dp.toPx(), 1.dp.toPx(), 0xff000000.toInt())
            val width = paint.measureText(entry.comment.text)
            val x = when (entry.comment.mode) {
                4, 5 -> ((size.width - width) / 2).coerceAtLeast(0f)
                6 -> -width + (size.width + width) * entry.progress
                else -> size.width - (size.width + width) * entry.progress
            }
            val y = when (entry.comment.mode) {
                4 -> size.height - (entry.lane + 2) * lineHeight
                else -> (entry.lane + 1) * lineHeight
            }.coerceIn(lineHeight, maxOf(lineHeight, size.height - lineHeight))
            drawContext.canvas.nativeCanvas.drawText(entry.comment.text, x, y, paint)
        }
    }
}
