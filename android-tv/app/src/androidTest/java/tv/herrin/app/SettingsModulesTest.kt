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

@OptIn(ExperimentalTestApi::class)
class SettingsModulesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fourModulesAndTwoProtocolsRemainNativeRemoteTargets() {
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
        val tops = listOf("WebDAV","SMB").map {
            compose.onNodeWithTag("personal-protocol-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        listOf("百度网盘", "阿里云盘", "夸克网盘").forEach {
            compose.onNodeWithTag("personal-protocol-$it").assertDoesNotExist()
        }
        assertEquals(1,tops.distinct().size)
        compose.onNodeWithTag("personal-protocol-WebDAV").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("personal-protocol-WebDAV").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.onNodeWithTag("personal-protocol-SMB").assertIsFocused()
        compose.onNodeWithTag("personal-protocol-SMB").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.runOnIdle { assertEquals("SMB",protocol) }
    }
    @Test fun protocolDownEntersNameAndSwitchingProtocolDiscardsOldForm() {
        var protocol by mutableStateOf("WebDAV")
        compose.setContent { MaterialTheme { Column {
            val name = remember { androidx.compose.ui.focus.FocusRequester() }
            PersonalProtocolTabs(protocol, true, name) { protocol = it }
            PersonalLibrarySettings(protocol, nameFocus = name, onChanged = {}, onPlay = {})
        } } }
        val tops = listOf("name","address","port","path","username","password").map { field ->
            compose.onNodeWithTag("personal-field-$field").assertExists().getUnclippedBoundsInRoot().top
        }
        assertEquals(tops.sorted(),tops)
        assertEquals(6,tops.distinct().size)
        compose.onNodeWithText("添加").assertExists()
        compose.onNodeWithText("导入当前媒体目录").assertDoesNotExist()
        compose.onNodeWithTag("personal-field-name").performTextInput("Old WebDAV")
        compose.onNodeWithTag("personal-protocol-WebDAV").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("personal-protocol-WebDAV").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionDown)
        }
        compose.onNodeWithTag("personal-field-name").assertIsFocused()
        compose.onNodeWithTag("personal-protocol-SMB").performClick()
        assertEquals("", compose.onNodeWithTag("personal-field-name").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("personal-field-host").assertExists()
        compose.onNodeWithTag("personal-field-address").assertDoesNotExist()
    }

}
