package tv.herrin.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.json.JSONObject
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only opaque ownership and QR graphics reach Compose. No URLs/credentials in text, semantics or logs. */
internal object SpiderAuthorizationUi {
    class Request(val owner: Any, val operation: Int, val dialog: Int,
                  val options: SpiderAuthorizationProtocol.Options, val reply: (String?) -> Unit)
    private val mutable = MutableStateFlow<Request?>(null)
    val requests = mutable.asStateFlow()
    val notice = MutableStateFlow<String?>(null)
    fun receive(owner: Any, operation: Int, dialog: Int, payload: String?, reply: (String?) -> Unit) {
        if (payload == null) {
            if (mutable.value?.let { it.owner === owner && it.operation == operation && it.dialog == dialog } == true) mutable.value = null
            return
        }
        val options = runCatching { SpiderAuthorizationProtocol.parse(payload) }.getOrElse { reply(null); return }
        val previous = mutable.value
        if (previous != null && (previous.owner !== owner || previous.operation != operation || previous.dialog != dialog)) previous.reply(null)
        mutable.value = Request(owner, operation, dialog, options, reply)
    }
    fun clear(owner: Any) {
        notice.value = null
        mutable.value?.takeIf { it.owner === owner }?.let { mutable.value = null; it.reply(null) }
    }
    fun cancel(request: Request, timeout: Boolean = false) {
        if (mutable.value === request) {
            mutable.value = null
            notice.value = if (timeout) "授权已超时，请重试。" else "授权已取消。"
            request.reply(null)
        }
    }
}

/** Mount once at the Activity root. A secure window prevents private QR screenshots/recordings. */
@Composable
internal fun SpiderAuthorizationHost() {
    val request by SpiderAuthorizationUi.requests.collectAsState()
    val notice by SpiderAuthorizationUi.notice.collectAsState()
    LaunchedEffect(notice) { if (notice != null) { delay(4000); SpiderAuthorizationUi.notice.value = null } }
    if (request == null) {
        notice?.let { message -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(message, color = Color.White, modifier = Modifier.padding(32.dp).background(Color(0xEE17191D))
                .padding(16.dp).testTag("spider-authorization-result"))
        } }
        return
    }
    val current = request!!
    val latest by rememberUpdatedState(current)
    var busy by remember(current) { mutableStateOf(false) }
    val focus = remember(current.dialog) { FocusRequester() }
    val inputFocus = remember(current.dialog) { FocusRequester() }
    var formValues by remember(current.owner, current.operation, current.dialog) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var formFeedback by remember(current.dialog) { mutableStateOf("") }
    val focusIndex = current.options.buttons.indexOfFirst { it.enabled }
    val bitmap = remember(current.options) { authorizationBitmap(current.options) }
    LaunchedEffect(current.owner, current.operation, current.dialog) {
        delay(SpiderAuthorizationProtocol.TIMEOUT_MS)
        SpiderAuthorizationUi.cancel(latest, timeout = true)
    }
    DisposableEffect(current.owner, current.operation, current.dialog) {
        onDispose { SpiderAuthorizationUi.cancel(latest) }
    }
    Dialog(onDismissRequest = { SpiderAuthorizationUi.cancel(current) }, properties = DialogProperties(
        dismissOnBackPress = true, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.width(560.dp).background(Color(0xFF17191D)).padding(28.dp)
            .testTag("spider-authorization-dialog"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (current.options.inputs.isNotEmpty()) "网盘访问授权" else "扫码授权",
                style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(if (busy) "正在确认授权状态…" else if (current.options.inputs.isNotEmpty())
                "请填写来源要求的访问码；若同时提供二维码，也可以扫码授权。"
                else "请使用对应平台扫描二维码，完成后选择确认。二维码将在两分钟后失效。",
                color = Color.White, modifier = Modifier.testTag("spider-authorization-status"))
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "授权二维码",
                modifier = Modifier.size(240.dp).testTag("spider-authorization-qr"))
            else if (current.options.inputs.isEmpty())
                Text("来源未提供二维码；如需扫码，请在源站点的登录入口发起授权。",
                    color = Color.White)
            current.options.inputs.forEachIndexed { index, input ->
                OutlinedTextField(
                    value = formValues[input.name].orEmpty(),
                    onValueChange = { new ->
                        if (new.length <= input.maxLength) {
                            formValues = formValues + (input.name to new)
                            formFeedback = ""
                        }
                    },
                    enabled = !busy && input.enabled,
                    singleLine = true,
                    visualTransformation = if (input.type == "password")
                        PasswordVisualTransformation() else VisualTransformation.None,
                    label = { Text(input.label, color = Color.White) },
                    modifier = (if (index == 0) Modifier.fillMaxWidth().focusRequester(inputFocus)
                        else Modifier.fillMaxWidth()).testTag("spider-authorization-input-${index}")
                )
            }
            if (formFeedback.isNotBlank()) Text(formFeedback, color = Color(0xFFFFB7A5),
                modifier = Modifier.testTag("spider-authorization-form-feedback"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                current.options.buttons.forEachIndexed { index, button ->
                    Button(onClick = {
                        if (!button.skipValidation && current.options.inputs.any {
                                it.enabled && it.required && formValues[it.name].isNullOrBlank()
                            }) {
                            formFeedback = "请填写来源要求的网盘访问码或授权内容"
                        } else {
                            val values = JSONObject()
                            current.options.inputs.forEach { values.put(it.name, formValues[it.name].orEmpty()) }
                            busy = true
                            current.reply(SpiderAuthorizationProtocol.event(button.id, values))
                        }
                    }, enabled = !busy && button.enabled,
                        modifier = (if (index == focusIndex) Modifier.focusRequester(focus) else Modifier)
                            .testTag("spider-authorization-action-$index")) { Text(button.label) }
                }
                Button(onClick = { SpiderAuthorizationUi.cancel(current) },
                    modifier = (if (focusIndex < 0) Modifier.focusRequester(focus) else Modifier)
                        .testTag("spider-authorization-cancel")) { Text("取消") }
            }
        }
        // Dialog has a separate composition/window. Its child must be attached
        // before requesting focus; an effect in the Activity composition races it.
        LaunchedEffect(current, focusIndex) {
            withFrameNanos { }
            if (current.options.inputs.isNotEmpty()) inputFocus.requestFocus() else focus.requestFocus()
        }
    }
}

private fun authorizationBitmap(options: SpiderAuthorizationProtocol.Options): Bitmap? = runCatching {
    options.qr?.let { value ->
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val colors = IntArray(480 * 480) { i -> if (matrix[i % 480, i / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return@runCatching Bitmap.createBitmap(colors, 480, 480, Bitmap.Config.ARGB_8888)
    }
    val bytes = options.image?.substringAfter(";base64,")?.let { Base64.decode(it, Base64.DEFAULT) } ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
