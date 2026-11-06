package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val ExitButtonBackground = Color(0xFF2A2A2C)
@Composable
internal fun ExitConfirmation(onCancel: () -> Unit, onExit: () -> Unit) {
    val cancel = remember { FocusRequester() }
    val exit = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { onCancel() }
        Surface(Modifier.width(600.dp).testTag("exit-dialog"), color = Color(0xFF171719), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("退出 HERRIN？", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("确定要退出应用吗？", color = Color(0xFFD3D3D5), fontSize = 18.sp)
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ExitAction("退出 App", "exit-confirm", exit, cancel, onExit)
                    ExitAction("再逛逛", "exit-cancel", cancel, exit, onCancel)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; cancel.requestFocus() }
    }
}
@Composable
private fun ExitAction(label: String, tag: String, requester: FocusRequester, neighbor: FocusRequester, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.width(224.dp).height(58.dp).testTag(tag)
        .focusRequester(requester).focusProperties { left = neighbor; right = neighbor; up = FocusRequester.Cancel; down = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .background(ExitButtonBackground, shape)
        .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
        .clickable(onClick = click).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
