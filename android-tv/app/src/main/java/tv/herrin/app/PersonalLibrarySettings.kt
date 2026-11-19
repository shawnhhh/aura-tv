package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Confined to personal settings; existing catalog and player surfaces remain unchanged. */
@Composable
internal fun PersonalLibrarySettings(tab: String, onChanged: () -> Unit, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { PersonalLibraryApi(context) }
    var data by remember { mutableStateOf(JSONObject()) }
    var status by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(2400); toast = "" } }
    var input by remember(tab) { mutableStateOf(JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else "443")) }
    var selected by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var browsePage by remember { mutableStateOf(0) }
    var browser by remember { mutableStateOf(JSONObject()) }
    var kind by remember { mutableStateOf("auto") }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var page by remember { mutableStateOf(0) }
    var category by remember { mutableStateOf("all") }
    var matchId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    fun execute(route: String, value: JSONObject = JSONObject(), browse: Boolean = false) {
        if(busy) return
        busy = true
        val requestedPage = browsePage
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.handle(route, if(browse) JSONObject(value.toString()).put("offset", requestedPage * 100) else value) }
                status = result.optString("error", result.optString("result", "已保存"))
                toast = if (result.optBoolean("ok", true)) "成功" else "失败"
                if(result.optBoolean("ok", true)) {
                    if(browse) browser = result
                    if(route == "/personal/search") matches = result
                    if(route == "/personal/save") {
                        selected = result.getString("id"); path = result.getString("path"); browsePage = 0; browser = result
                        input = JSONObject(input.toString()).put("id", selected).also { it.remove("password") }
                    }
                    data = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject().put("offset", page * 100).put("category", category)) }
                    onChanged()
                }
            } catch(_: Exception) { status = "操作失败"; toast = "失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(page, category) {
        while(isActive) {
            data = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject().put("offset", page * 100).put("category", category)) }
            delay(1500)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toast.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(toast)
        }
        if(data.optBoolean("migrationWarning")) Text("部分旧配置无效，原配置已保留；请手动添加对应连接。")
        val connections = data.optJSONArray("connections")
        if(connections != null) for(i in 0 until connections.length()) {
            val c = connections.getJSONObject(i)
            if((c.getString("protocol") == "smb") != (tab == "SMB")) continue
            Text(c.getString("name") + (if(c.optBoolean("enabled")) " · 启用" else " · 停用") + " · " + c.optString("status", "unknown"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("编辑") { input = JSONObject(c.toString()); selected = c.getString("id"); path = c.optString("path"); browsePage = 0; browser = JSONObject() }
                TvButton("浏览目录") { selected = c.getString("id"); browsePage = 0; execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
                TvButton("启用/停用") { execute("/personal/toggle", JSONObject().put("id", c.getString("id"))) }
                TvButton("删除") { confirm = "/personal/delete" to c.getString("id") }
            }
        }
        TvButton("新建连接") { input = JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else "443"); selected = ""; path = "" }
        val fields = if (tab == "SMB")
            listOf("name" to "名称", "host" to "地址", "port" to "端口",
                "username" to "用户名", "password" to "密码")
        else listOf("name" to "名称", "address" to "地址", "username" to "用户名", "password" to "密码")
        fields.forEach { (key, label) ->
            OutlinedTextField(input.optString(key), { value -> input = JSONObject(input.toString()).put(key, value.take(if(key == "password") 2048 else 256)) }, modifier = Modifier.testTag("personal-field-$key"), label = { Text(label) },
                singleLine = true, visualTransformation = if(key == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
        if(tab == "SMB") TvButton("SMB3 加密：${input.optBoolean("encryption")}") { input = JSONObject(input.toString()).put("encryption", !input.optBoolean("encryption")) }
        OutlinedTextField(path, { path = it.take(2048) }, label = { Text("路径") })
        TvButton(if(busy) "处理中…" else "添加") { if(!busy) execute("/personal/save", JSONObject(input.toString()).put("path", path)) }
        if(selected.isNotEmpty()) {
            TvButton("测试 / 浏览当前目录") { execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            TvButton("返回上级") { browsePage = 0; path = path.substringBeforeLast('/', ""); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            val files = browser.optJSONArray("files")
            if(files != null) for(i in 0 until minOf(100, files.length())) {
                val f = files.getJSONObject(i)
                TvButton(f.getString("name")) { browsePage = 0; path = f.getString("path"); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            }
            Row {
                TvButton("目录上一页") { browsePage = (browsePage - 1).coerceAtLeast(0); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
                TvButton("目录下一页") { if((browsePage + 1) * 100 < browser.optInt("total")) { browsePage++; execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) } }
            }
            TvButton("导入当前媒体目录") { execute("/personal/folder", JSONObject().put("connection", selected).put("path", path).put("type", "auto")) }
        }
        Text("文件夹管理")
        val folders = data.optJSONArray("folders")
        if(folders != null) for(i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            Text(f.getString("path") + " · " + f.getString("type") + " · " + f.optString("state"))
            Row {
                TvButton("刷新") { execute("/personal/refresh", JSONObject().put("id", f.getString("id"))) }
                TvButton("类型设为 $kind") { execute("/personal/folder", JSONObject(f.toString()).put("type", kind).put("scan", false)) }
                TvButton("启用/停用") { execute("/personal/folder", JSONObject(f.toString()).put("enabled", !f.optBoolean("enabled")).put("scan", false)) }
                TvButton("移除") { confirm = "/personal/folder/delete" to f.getString("id") }
            }
        }

    }
    confirm?.let { (route, id) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认删除配置？服务器文件会保留。") },
        confirmButton = { TextButton(onClick = { execute(route, JSONObject().put("id", id).put("confirmed", true)); confirm = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
