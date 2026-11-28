package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

private data class DirectorySelection(val id: String, val path: String, val page: Int = 0)

@Composable
internal fun PersonalLibrarySettings(tab: String, nameFocus: FocusRequester? = null, onChanged: () -> Unit) {
    key(tab) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val api = remember { PersonalLibraryApi(context) }
        fun newForm() = JSONObject().put("protocol", if (tab == "SMB") "smb" else "https")
            .put("port", if (tab == "SMB") "445" else "")
        var data by remember { mutableStateOf(JSONObject()) }
        var input by remember { mutableStateOf(newForm()) }
        var formRevision by remember { mutableLongStateOf(0L) }
        var toast by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf<String?>(null) }
        var selection by remember { mutableStateOf<DirectorySelection?>(null) }
        var directory by remember { mutableStateOf<Pair<DirectorySelection, JSONObject>?>(null) }
        LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(2400); toast = "" } }
        BackHandler(selection != null && confirm == null) { selection = null }

        suspend fun loadSettings() = withContext(Dispatchers.IO) { api.handle("/personal", JSONObject()) }
        LaunchedEffect(Unit) {
            try { data = loadSettings() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("HerrinPersonal", "Settings read failed: " + failure.javaClass.simpleName)
            }
        }
        LaunchedEffect(selection) {
            directory = null
            val current = selection ?: return@LaunchedEffect
            try {
                val result = withContext(Dispatchers.IO) {
                    api.handle("/personal/browse", JSONObject().put("id", current.id)
                        .put("path", current.path).put("offset", current.page * 100))
                }
                if (selection != current) return@LaunchedEffect
                if (result.optBoolean("ok")) directory = current to result else toast = "无法打开目录"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (selection == current) toast = "无法打开目录"
                android.util.Log.w("HerrinPersonal", "Directory read failed: " + failure.javaClass.simpleName)
            }
        }
        fun execute(route: String, payload: JSONObject) {
            if (busy) return
            busy = true
            val revision = formRevision
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { api.handle(route, payload) }
                    if (!result.optBoolean("ok", true)) {
                        toast = "失败"
                        return@launch
                    }
                    if (route == "/personal/save" && revision == formRevision) input = newForm()
                    if (route == "/personal/delete" || route == "/personal/toggle") {
                        if (selection?.id == payload.optString("id")) selection = null
                    }
                    data = loadSettings()
                    onChanged()
                    toast = "成功"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("HerrinPersonal", "Settings update failed: " + failure.javaClass.simpleName)
                    toast = "失败"
                } finally { busy = false }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (toast.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text(toast) }
            val connections = data.optJSONArray("connections")
            for (i in 0 until (connections?.length() ?: 0)) {
                val entry = connections!!.getJSONObject(i)
                if ((entry.getString("protocol") == "smb") != (tab == "SMB")) continue
                val id = entry.getString("id")
                val enabled = entry.optBoolean("enabled")
                val browsing = selection?.takeIf { it.id == id }
                Text(entry.getString("name") + if (enabled) " · 启用" else " · 停用")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("编辑") { formRevision++; input = JSONObject(entry.toString()); selection = null }
                    if (enabled) TvButton("浏览目录", Modifier.testTag("personal-browse-$id")) {
                        selection = if (browsing == null) DirectorySelection(id, entry.optString("path")) else null
                    }
                    TvButton(if (enabled) "停用" else "启用") { execute("/personal/toggle", JSONObject().put("id", id)) }
                    TvButton("删除") { confirm = id }
                }
                if (browsing != null) Column(Modifier.testTag("personal-directory"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val result = directory?.takeIf { it.first == browsing }?.second
                    Text(result?.optString("displayPath") ?: "/" + browsing.path)
                    if (result == null) {
                        if (toast.isBlank()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        if (!result.isNull("parent")) TvButton("上级目录") {
                            selection = DirectorySelection(id, result.getString("parent"))
                        }
                        val entries = result.getJSONArray("files")
                        for (j in 0 until entries.length()) {
                            val file = entries.getJSONObject(j)
                            if (file.optBoolean("directory")) TvButton(file.getString("name")) {
                                selection = DirectorySelection(id, file.getString("path"))
                            } else Text(file.getString("name"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (browsing.page > 0) TvButton("上一页") { selection = browsing.copy(page = browsing.page - 1) }
                            if ((browsing.page + 1) * 100 < result.optInt("total"))
                                TvButton("下一页") { selection = browsing.copy(page = browsing.page + 1) }
                        }
                    }
                }
            }

            val addressField = if (tab == "SMB") "host" else "address"
            val fields = listOf("name" to "连接名称", addressField to "服务器地址", "port" to "端口",
                "path" to "路径", "username" to "用户名", "password" to "密码")
            fields.forEach { (field, label) ->
                TvInputField(input.optString(field), { value ->
                    formRevision++
                    val max = if (field in setOf("address", "password", "path")) 2048 else 256
                    input = JSONObject(input.toString()).put(field, value.take(max))
                }, modifier = Modifier.testTag("personal-field-$field").then(
                    if (field == "name" && nameFocus != null) Modifier.focusRequester(nameFocus) else Modifier),
                    label = { Text(label) }, singleLine = true,
                    visualTransformation = if (field == "password") PasswordVisualTransformation() else VisualTransformation.None)
            }
            if (tab == "SMB") TvButton("SMB3 加密：${if (input.optBoolean("encryption")) "开" else "关"}") {
                formRevision++; input = JSONObject(input.toString()).put("encryption", !input.optBoolean("encryption"))
            }
            if (input.has("id")) TvButton("取消编辑") { formRevision++; input = newForm() }
            TvButton(if (input.has("id")) "保存" else "添加") { execute("/personal/save", JSONObject(input.toString())) }
        }
        confirm?.let { id -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除此连接？") },
            confirmButton = { TextButton(onClick = {
                execute("/personal/delete", JSONObject().put("id", id).put("confirmed", true))
                confirm = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        ) }
    }
}
