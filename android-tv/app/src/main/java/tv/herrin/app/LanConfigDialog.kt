package tv.herrin.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

@Composable
internal fun LanConfigDialog(onDismiss: () -> Unit, onApplied: (String) -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onApplied)
    var server by remember { mutableStateOf<LanConfigServer?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在开启临时 HTTPS 服务…") }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        val started = withContext(Dispatchers.IO) {
            runCatching {
                val host = NetworkInterface.getNetworkInterfaces().toList().asSequence()
                    .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList().asSequence() }
                    .first { it is java.net.Inet4Address && it.isSiteLocalAddress }.hostAddress!!
                LanConfigServer(context, host) { module -> main.post { callback(module) } }
                    .apply { start(5000, true) }
            }.getOrNull()
        }
        if (started == null) { message = "无法开启局域网服务，请使用遥控器手动配置"; return@LaunchedEffect }
        server = started
        bitmap = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(started.qr, BarcodeFormat.QR_CODE, 480, 480)
            val colors = IntArray(480 * 480) { i -> if (matrix[i % 480, i / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(colors, 480, 480, Bitmap.Config.ARGB_8888)
        }
        message = "${started.origin}\n证书 SHA-256：\n${started.fingerprint}\n配对有效期 3 分钟；已确认会话闲置 30 分钟后结束。"
        while (!started.pairing.expired()) {
            pending = started.pairing.pending()
            delay(500)
        }
        started.end(); message = "会话已结束，请重新开启"; bitmap = null; pending = false
    }
    DisposableEffect(Unit) { onDispose { server?.end() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手机扫码配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("四个模块共用此次配对。自签名 HTTPS 必须在手机核对证书指纹；无法核对时不要输入敏感信息。", fontSize = 15.sp)
                bitmap?.let { Image(it.asImageBitmap(), "手机扫码配置二维码", Modifier.size(220.dp)) }
                Text(message, fontSize = 13.sp)
                if (pending) TvButton("确认此手机配对") { server?.pairing?.confirm(); pending = false }
            }
        }, confirmButton = { TvButton("结束配置", onClick = onDismiss) })
}
