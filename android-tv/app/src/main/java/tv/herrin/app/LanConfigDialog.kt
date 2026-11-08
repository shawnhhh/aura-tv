package tv.herrin.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

internal class NoLanAddress : java.io.IOException()
internal fun openLanConfigServer(context: Context, applied: (String) -> Unit): LanConfigServer =
    openLanConfigServer(context, false, applied)

internal fun openLanConfigServer(context: Context, quickHttp: Boolean, applied: (String) -> Unit): LanConfigServer {
    val host = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().asSequence()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList().asSequence() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
        ?: throw NoLanAddress()
    return LanConfigServer(context, host, quickHttp, applied)
}
internal fun configurationQr(value: String): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480)
    val colors = IntArray(480*480) { i -> if (matrix[i%480,i/480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(colors,480,480,Bitmap.Config.ARGB_8888)
}

/** Separate modal window: QR and controls never live inside the settings LazyColumn. */
@Composable
internal fun LanConfigDialog(onDismiss: () -> Unit, onApplied: (String) -> Unit,
    serverFactory: (Context, (String) -> Unit) -> LanConfigServer = ::openLanConfigServer,
    qrFactory: (String) -> Bitmap = ::configurationQr,
    quickServerFactory: (Context, (String) -> Unit) -> LanConfigServer =
        { context, applied -> openLanConfigServer(context, true, applied) }) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onApplied)
    var server by remember { mutableStateOf<LanConfigServer?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pending by remember { mutableStateOf(false) }
    var quickHttp by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在开启临时 HTTPS 服务…") }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(epoch, quickHttp) {
        var created: LanConfigServer? = null
        bitmap = null; failed = false; pending = false
        message = if (quickHttp) "正在开启临时 HTTP 服务…" else "正在开启临时 HTTPS 服务…"
        try {
            val started = withContext(Dispatchers.IO) {
                (if (quickHttp) quickServerFactory(context) { module -> main.post { callback(module) } }
                else serverFactory(context) { module -> main.post { callback(module) } })
                    .also { created = it }.apply { start(5000, true) }
            }
            check(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            server = started
            bitmap = withContext(Dispatchers.Default) { qrFactory(started.qr) }
            message = if (quickHttp) "配对 3 分钟有效；服务开启后最多 5 分钟，退出立即关闭。" else "配对 3 分钟有效；确认后闲置 30 分钟结束。"
            while (!started.pairing.expired()) {
                pending = started.pairing.pending()
                delay(350)
            }
            message = "会话已结束，请重新开启"
            failed = true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: NoLanAddress) {
            failed = true; message = "没有可用局域网 IPv4。请检查电视网络与手机是否互通；也可使用遥控器手动配置。"
        } catch (_: Exception) {
            failed = true; message = "服务启动或二维码生成失败。请重试，或使用遥控器手动配置。"
        } finally {
            created?.end(); server = null; bitmap = null; pending = false
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) server?.end()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            server?.end()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(800.dp).testTag("phone-config-dialog"), color = Color(0xFF171719),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("手机扫码配置", fontSize = 26.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(232.dp).background(Color.White), contentAlignment = Alignment.Center) {
                        val qr = bitmap
                        if (qr != null) Image(qr.asImageBitmap(), "手机扫码配置二维码", Modifier.fillMaxSize().testTag("phone-config-qr"))
                        else Text(if (failed) "二维码不可用" else "正在准备二维码…", color = Color.Black)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (quickHttp) "仅添加和修改公开影视源，不接受任何敏感配置。" else "四个模块共用会话：TMDB、影视源、直播、NAS。", fontSize = 16.sp, color = Color.White)
                        server?.let { current ->
                            Text(current.origin, fontSize = 17.sp, color = Color.White, modifier = Modifier.testTag("phone-config-address"))
                            if (!quickHttp) Text("证书 SHA-256：\n${current.fingerprint}", fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFFD4D4D6))
                        }
                        Text(if (quickHttp) "HTTP 明文传输，仅供可信家庭局域网临时使用。勿填写账号、密码、Token 或私密链接；配对码不能防止局域网中间人攻击。" else "自签名 HTTPS 需要在手机核对完整证书指纹。Safari 等浏览器可能限制访问；无法核对时请手动配置。", fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFD4D4D6))
                        Text(message, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White, modifier = Modifier.testTag("phone-config-status"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton(if (quickHttp) "返回 HTTPS 配置" else "局域网快速配置", Modifier.testTag("phone-config-http")) {
                        server?.end(); quickHttp = !quickHttp
                    }
                    if (pending) TvButton("确认此手机配对", Modifier.testTag("phone-config-approve")) { server?.pairing?.confirm() }
                    if (failed) TvButton("重试", Modifier.testTag("phone-config-retry")) { epoch++ }
                    TvButton("结束配置", Modifier.testTag("phone-config-close"), onClick = onDismiss)
                }
            }
        }
    }
}
