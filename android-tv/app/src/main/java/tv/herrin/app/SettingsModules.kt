package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
internal fun PersonalProtocolTabs(selected: String, reduced: Boolean, nameFocus: FocusRequester? = null, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("WebDAV", "SMB").forEach { label ->
            TvButton(label, Modifier.testTag("personal-protocol-$label").focusProperties { if(nameFocus != null) down = nameFocus }, reduced,
                selected = selected == label) { select(label) }
        }
    }
}
