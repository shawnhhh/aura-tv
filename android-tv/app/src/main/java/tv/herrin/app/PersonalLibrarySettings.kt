package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.*
import org.json.JSONObject

/** Confined to personal settings; existing catalog and player surfaces remain unchanged. */
@Composable
internal fun PersonalLibrarySettings(tab: String, nameFocus: FocusRequester? = null, onChanged: () -> Unit, onPlay: (String) -> Unit) {
    key(tab) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { PersonalLibraryApi(context) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var data by remember { mutableStateOf(JSONObject()) }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(2400); toast = "" } }
    var input by remember(tab) { mutableStateOf(JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else "")) }
    var selected by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var browsePage by remember { mutableStateOf(0) }
    var browser by remember { mutableStateOf(JSONObject()) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var formRevision by remember { mutableLongStateOf(0L) }
    fun execute(route: String, value: JSONObject = JSONObject(), browse: Boolean = false) {
        if(busy) return
        busy = true
        if (browse) browser = JSONObject()
        val requestedRevision = formRevision
        val requestedPage = browsePage
        val requestedInput = input.toString()
        // The passed request owns its path/connection; click state writes can
        // be observed by Compose on the next frame rather than this closure.
        val requestedPath = if(browse) value.optString("path") else path
        val requestedConnection = selected
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.handle(route, if(browse) JSONObject(value.toString()).put("offset", requestedPage * 100) else value) }
                if (route != "/personal/save" || requestedRevision == formRevision)
                    toast = if (result.optBoolean("ok", true)) "成功" else "失败"
                if(result.optBoolean("ok", true)) {
                    if (browse && requestedRevision == formRevision && requestedPath == path && requestedConnection == selected && requestedPage == browsePage) browser = result
                    if(route == "/personal/save") {
                        val sameForm = requestedRevision == formRevision && input.toString() == requestedInput && path == requestedPath
                        if(sameForm && input.optString("password") == value.optString("password")) {
                            input = JSONObject(input.toString()).also { it.remove("password") }
                        }
                        if(sameForm) {
                            selected = result.getString("id"); path = result.getString("path"); browsePage = 0; browser = JSONObject()
                            input = JSONObject(input.toString()).put("id", selected)
                        }
                    }
                    data = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject()) }
                    if (route in setOf("/personal/save", "/personal/delete", "/personal/toggle",
                            "/personal/folder", "/personal/folder/delete")) onChanged()
                }
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) {
                android.util.Log.w("HerrinPersonal", "Personal operation failed: " + error.javaClass.simpleName)
                if (route != "/personal/save" || requestedRevision == formRevision) toast = "失败"
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { data = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject()) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            android.util.Log.w("HerrinPersonal", "Personal settings load failed: " + error.javaClass.simpleName)
        }
    }
    fun browse() = execute("/personal/preview", JSONObject(input.toString()).put("path", path), true)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toast.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(toast)
        }
        if(data.optBoolean("migrationWarning")) Text("部分旧配置无效，原配置已保留；请手动添加对应连接。")
        val connections = data.optJSONArray("connections")
        if(connections != null) for(i in 0 until connections.length()) {
            val c = connections.getJSONObject(i)
            if((c.getString("protocol") == "smb") != (tab == "SMB")) continue
            Text(c.getString("name") + (if(c.optBoolean("enabled")) " · 启用" else " · 停用"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("编辑") { formRevision++; input = JSONObject(c.toString()); selected = c.getString("id"); path = c.optString("path"); browsePage = 0; browser = JSONObject() }
                TvButton("启用/停用") { execute("/personal/toggle", JSONObject().put("id", c.getString("id"))) }
                TvButton("删除") { confirm = "/personal/delete" to c.getString("id") }
            }
        }
        TvButton("新建连接") { formRevision++; input = JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else ""); selected = ""; path = ""; browser = JSONObject(); browsePage = 0 }
        val fields = if (tab == "SMB")
            listOf("name" to "连接名称", "host" to "服务器地址", "port" to "端口", "path" to "路径",
                "username" to "用户名", "password" to "密码")
        else listOf("name" to "连接名称", "address" to "服务器地址", "port" to "端口", "path" to "路径", "username" to "用户名", "password" to "密码")
        fields.forEach { (key, label) ->
            OutlinedTextField(if(key == "path") path else input.optString(key), { value ->
                formRevision++
                browser = JSONObject()
                browsePage = 0
                if(key == "path") path = value.take(2048)
                else input = JSONObject(input.toString()).put(key, value.take(if(key in setOf("password", "address")) 2048 else 256))
            }, modifier = Modifier.testTag("personal-field-$key").onPreviewKeyEvent { event ->
                if(event.type != KeyEventType.KeyDown) false else when(event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                    Key.Enter, Key.DirectionCenter -> { keyboard?.show(); true }
                    else -> false
                }
            }.then(
                    if(key == "name" && nameFocus != null) Modifier.focusRequester(nameFocus) else Modifier), label = { Text(label) },
                singleLine = true, visualTransformation = if(key == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
        if(tab == "SMB") TvButton("SMB3 加密：${input.optBoolean("encryption")}") { formRevision++; input = JSONObject(input.toString()).put("encryption", !input.optBoolean("encryption")) }
        TvButton("浏览目录") { if (!busy) { browsePage = 0; browse() } }
        if (browser.has("files")) {
            TvButton("上级目录") { if(!busy) { formRevision++; browsePage = 0; path = path.substringBeforeLast('/', ""); browse() } }
            val files = browser.optJSONArray("files")
            if (browser.optBoolean("ok") && files?.length() == 0) Text("此目录下暂无文件夹")
            if(files != null) for(i in 0 until minOf(100, files.length())) {
                val f = files.getJSONObject(i)
                TvButton(f.getString("name")) { if(!busy) { formRevision++; browsePage = 0; path = f.getString("path"); browse() } }
            }
            Row {
                TvButton("目录上一页") { if(!busy) { browsePage = (browsePage - 1).coerceAtLeast(0); browse() } }
                TvButton("目录下一页") { if(!busy && (browsePage + 1) * 100 < browser.optInt("total")) { browsePage++; browse() } }
            }
        }
        TvButton("添加") { if (!busy) execute("/personal/save", JSONObject(input.toString()).put("path", path)) }
        Text("已选文件夹")
        val folders = data.optJSONArray("folders")
        if(folders != null) for(i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            val owner = (0 until (connections?.length() ?: 0)).map { connections!!.getJSONObject(it) }
                .firstOrNull { it.optString("id") == f.optString("connection") }
            if(owner == null || (owner.optString("protocol") == "smb") != (tab == "SMB")) continue
            Text(f.getString("path").ifBlank { owner.optString("basePath").ifBlank { owner.getString("name") } })
            Row {
                TvButton("移除") { confirm = "/personal/folder/delete" to f.getString("id") }
            }
        }

    }
    confirm?.let { (route, id) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认删除配置？服务器文件会保留。") },
        confirmButton = { TextButton(onClick = { execute(route, JSONObject().put("id", id).put("confirmed", true)); confirm = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
}
