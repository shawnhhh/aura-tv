package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun SettingsModules(selected: String, reduced: Boolean, select: (String) -> Unit) {
    Column(Modifier.width(180.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("影视源", "个人媒体库", "海报与资料", "直播源").forEach { label ->
            TvButton(label, Modifier.fillMaxWidth().testTag("settings-module-$label"), reduced,
                selected = selected == label) { select(label) }
        }
    }
}

@Composable
internal fun PersonalProtocolTabs(selected: String, reduced: Boolean, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("WebDAV", "SMB").forEach { label ->
            TvButton(label, Modifier.testTag("personal-protocol-$label"), reduced,
                selected = selected == label) { select(label) }
        }
    }
}

@Composable
internal fun SmbProtocolSettings(onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HerrinSmbConfigStore(context) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var encryption by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { store.account() }
        if (saved != null) { url = saved.url; user = saved.username; domain = saved.domain; encryption = saved.encryption }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(url, { url = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("smb://服务器/共享/路径") }, singleLine = true)
        OutlinedTextField(user, { user = it.take(256) }, label = { Text("用户名") }, singleLine = true)
        OutlinedTextField(domain, { domain = it.take(256) }, label = { Text("域（可留空）") }, singleLine = true)
        OutlinedTextField(password, { password = it.take(2048) }, label = { Text("密码（留空保留原值）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        TvButton("SMB 加密：${if (encryption) "开" else "关"}") { encryption = !encryption }
        TvButton("保存 SMB 配置") {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { store.save(JSONObject().put("url", url).put("username", user)
                        .put("password", password).put("domain", domain).put("encryption", encryption)) }.isSuccess
                }
                status = if (saved) "配置已保存；连接与播放结果以实际访问为准" else "配置保存失败，请检查地址或设备安全存储"
                if (saved) { password = ""; onSaved() }
            }
        }
        Text(status)
    }
}
