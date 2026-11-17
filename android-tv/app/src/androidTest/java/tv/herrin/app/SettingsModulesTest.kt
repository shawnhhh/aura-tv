package tv.herrin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsModulesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fourModulesAndFiveProtocolsRemainNativeRemoteTargets() {
        var module by mutableStateOf("影视源")
        var protocol by mutableStateOf("WebDAV")
        compose.setContent { MaterialTheme { Row {
            SettingsModules(module, true) { module = it }
            Column { PersonalProtocolTabs(protocol, true) { protocol = it } }
        } } }
        listOf("影视源","个人媒体库","海报与资料","直播源").forEach {
            compose.onNodeWithTag("settings-module-$it").assertHasClickAction()
        }
        compose.onNodeWithTag("settings-module-个人媒体库").performClick()
        compose.runOnIdle { assertEquals("个人媒体库",module) }
        val tops = listOf("WebDAV","SMB","百度网盘","阿里云盘","夸克网盘").map {
            compose.onNodeWithTag("personal-protocol-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(1,tops.distinct().size)
        compose.onNodeWithTag("personal-protocol-WebDAV").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("personal-protocol-WebDAV").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.onNodeWithTag("personal-protocol-SMB").assertIsFocused()
        compose.onNodeWithTag("personal-protocol-SMB").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.runOnIdle { assertEquals("SMB",protocol) }
    }
}
