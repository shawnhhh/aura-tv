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
    var input by remember(tab) { mutableStateOf(JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else "443")) }
    var selected by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
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
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { api.handle(route, value) }
                status = result.optString("error", result.optString("result", "已保存"))
                if(result.optBoolean("ok", true)) {
                    if(browse) browser = result
                    if(route == "/personal/search") matches = result
                    if(route == "/personal/save") { selected = result.getString("id"); path = result.getString("path"); input.remove("password") }
                    data = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject().put("offset", page * 100).put("category", category)) }
                    onChanged()
                }
            } catch(_: Exception) { status = "操作失败：请检查字段、目录重叠或是否已有扫描任务" }
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
        Text("存储连接管理 · $tab")
        val connections = data.optJSONArray("connections")
        if(connections != null) for(i in 0 until connections.length()) {
            val c = connections.getJSONObject(i)
            if((c.getString("protocol") == "smb") != (tab == "SMB")) continue
            Text(c.getString("name") + if(c.optBoolean("enabled")) " · 启用" else " · 停用")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("编辑") { input = JSONObject(c.toString()); selected = c.getString("id") }
                TvButton("浏览目录") { selected = c.getString("id"); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
                TvButton("启用/停用") { execute("/personal/toggle", JSONObject().put("id", c.getString("id"))) }
                TvButton("删除") { confirm = "/personal/delete" to c.getString("id") }
            }
        }
        TvButton("新建连接") { input = JSONObject().put("protocol", if(tab == "SMB") "smb" else "https").put("port", if(tab == "SMB") "445" else "443"); selected = ""; path = "" }
        if(tab != "SMB") Row {
            listOf("https", "http").forEach { protocol -> TvButton(protocol.uppercase()) {
                input = JSONObject(input.toString()).put("protocol", protocol).put("port", if(protocol == "https") "443" else "80")
            } }
        }
        if(input.optString("protocol") == "http") Text("HTTP 使用明文传输，请确认服务器可信。")
        listOf("name" to "名称", "host" to "IP 或域名", "port" to "端口", "username" to "用户名（选填）", "password" to "密码（编辑时留空保留）").plus(if(tab == "SMB") listOf("domain" to "域（选填）") else emptyList()).forEach { (key, label) ->
            OutlinedTextField(input.optString(key), { value -> input = JSONObject(input.toString()).put(key, value.take(if(key == "password") 2048 else 256)) }, modifier = Modifier.testTag("personal-field-$key"), label = { Text(label) },
                singleLine = true, visualTransformation = if(key == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
        if(tab == "SMB") TvButton("SMB3 加密：${input.optBoolean("encryption")}") { input = JSONObject(input.toString()).put("encryption", !input.optBoolean("encryption")) }
        OutlinedTextField(path, { path = it.take(2048) }, label = { Text(if(tab == "SMB") "共享名称/目录" else "路径（选填）") })
        TvButton(if(busy) "处理中…" else "确认添加 / 保存并验证") { if(!busy) execute("/personal/save", JSONObject(input.toString()).put("path", path)) }
        if(selected.isNotEmpty()) {
            TvButton("测试 / 浏览当前目录") { execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            TvButton("返回上级") { path = path.substringBeforeLast('/', ""); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            val files = browser.optJSONArray("files")
            if(files != null) for(i in 0 until minOf(100, files.length())) {
                val f = files.getJSONObject(i)
                TvButton(f.getString("name")) { path = f.getString("path"); execute("/personal/browse", JSONObject().put("id", selected).put("path", path), true) }
            }
            Text("媒体类型：$kind")
            Row { listOf("auto" to "自动", "movie" to "电影", "tv" to "剧集", "anime" to "动漫", "video" to "其他").forEach { (key, label) -> TvButton(label) { kind = key } } }
            TvButton("导入当前媒体目录") { execute("/personal/folder", JSONObject().put("connection", selected).put("path", path).put("type", kind)) }
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
        Text(data.optString("progress"))
        TvButton("取消扫描") { execute("/personal/cancel") }
        Text("已导入电影 / 剧集 / 未识别视频")
        Row { listOf("all" to "全部", "movie" to "电影", "tv" to "剧集", "unmatched" to "未识别").forEach { (key, label) ->
            TvButton(label) { category = key; page = 0 }
        } }
        val media = data.optJSONArray("media")
        val groups = (0 until (media?.length() ?: 0)).map { media!!.getJSONObject(it) }.groupBy {
            if(it.optInt("tmdb") > 0) "${it.getString("type")}:${it.getString("tmdb")}:${it.optString("season")}:${it.optString("episode")}" else it.getString("id")
        }
        groups.values.forEach { versions ->
            Text(versions.first().getString("title") + if(versions.size > 1) " · ${versions.size} 个版本" else "")
            versions.forEach { row -> Row {
                TvButton(row.getString("name")) { onPlay(row.getString("id")) }
                TvButton("匹配") { matchId = row.getString("id"); query = row.getString("title") }
            } }
        }
        Row {
            TvButton("上一页") { page = (page - 1).coerceAtLeast(0) }
            Text("第 ${page + 1} 页")
            TvButton("下一页") { if((media?.length() ?: 0) == 100) page++ }
        }
        if(matchId.isNotEmpty()) {
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索 TMDB 并纠正匹配") })
            TvButton("搜索影片") { execute("/personal/search", JSONObject().put("query", query)) }
            val results = matches.optJSONArray("matches")
            if(results != null) for(i in 0 until results.length()) {
                val m = results.getJSONObject(i)
                TvButton(m.getString("title") + " · " + m.getString("year")) {
                    execute("/personal/match", JSONObject().put("id", matchId).put("tmdb", m.getInt("tmdb")).put("type", m.getString("type")).put("title", m.getString("title")))
                    matchId = ""
                }
            }
        }
        Text(status)
    }
    confirm?.let { (route, id) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认删除配置？服务器文件会保留。") },
        confirmButton = { TextButton(onClick = { execute(route, JSONObject().put("id", id).put("confirmed", true)); confirm = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
