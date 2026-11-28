package tv.herrin.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Scroll to the real selected track before asking its mounted control for focus. */
@Composable
internal fun PlayerControlMenu(name: String, speed: Float, options: List<PlaybackTransport.Option>,
    focusEnabled: Boolean, onSpeed: (Float) -> Unit, onTrack: (Int, Int) -> Unit) {
    val speeds = PlaybackTransport.speeds.reversed()
    val text = name == "字幕"
    val selected = options.indexOfFirst { it.selected }
    val index = when (name) {
        "倍速" -> speeds.indexOfFirst { kotlin.math.abs(it - speed) < .01f }.coerceAtLeast(0)
        "字幕" -> if (selected < 0) 0 else selected + 1
        else -> selected.coerceAtLeast(0)
    }
    val focus = remember(name, index, options) { FocusRequester() }
    var mounted by remember(focus) { mutableStateOf(false) }
    val target = Modifier.focusRequester(focus).onGloballyPositioned { mounted = true }
    val state = rememberLazyListState()
    LaunchedEffect(name, index, options) { state.scrollToItem(index) }
    LaunchedEffect(focus, mounted, focusEnabled) {
        if (mounted && focusEnabled) { withFrameNanos { }; focus.requestFocus() }
    }
    LazyColumn(state = state, modifier = Modifier.testTag("player-menu-options")) {
        when (name) {
            "倍速" -> itemsIndexed(speeds) { row, value ->
                PlayerTextButton(onClick = { onSpeed(value) }, modifier = if (row == index) target else Modifier) {
                    Text((if (kotlin.math.abs(value - speed) < .01f) "✓ " else "") + "${value}×")
                }
            }
            "音轨", "字幕" -> {
                if (text) item {
                    PlayerTextButton(onClick = { onTrack(-1, -1) }, modifier = if (index == 0) target else Modifier) { Text("关闭字幕") }
                }
                if (options.isEmpty()) item {
                    Box((if (text) Modifier else target.focusable()).testTag("player-menu-empty").padding(12.dp)) {
                        Text(if (text) "暂无可用字幕" else "暂无可用音轨", color = Color.White)
                    }
                }
                itemsIndexed(options) { row, option ->
                    PlayerTextButton(onClick = { onTrack(option.groupIndex, option.trackIndex) },
                        modifier = (if (row + (if (text) 1 else 0) == index) target else Modifier)
                            .testTag("player-track-${option.groupIndex}-${option.trackIndex}")) {
                        Text((if (option.selected) "✓ " else "") + option.label)
                    }
                }
            }
            else -> item {
                Box(target.focusable().testTag("player-menu-empty").padding(12.dp)) { Text("暂无可用版本或集数", color = Color.White) }
            }
        }
    }
}
