package tv.herrin.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M466DetailCastDpadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dpadDownScrollsOffscreenCastIntoViewAndFocusesFirstActor() {
        val episodeRequester = FocusRequester()
        compose.setContent {
            val list = rememberLazyListState()
            val first = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            var castFocused by remember { mutableStateOf(false) }
            LazyColumn(Modifier.width(520.dp).height(220.dp).testTag("cast-test-list"),
                state = list) {
                item {
                    Box(Modifier.width(300.dp).height(60.dp).testTag("cast-test-episode")
                        .focusRequester(episodeRequester)
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                                DetailCastFocus.move(scope, list, true, first) { castFocused }
                        }.focusable()) { Text("第1集") }
                }
                items((1..12).toList()) { i ->
                    Text("剧情详情行 $i", Modifier.height(64.dp))
                }
                item {
                    Box(Modifier.width(140.dp).height(90.dp)
                        .testTag("cast-test-first-actor")
                        .focusRequester(first)
                        .onFocusChanged { castFocused = it.isFocused }
                        .focusable()) { Text("演员1") }
                }
            }
        }
        compose.runOnIdle { episodeRequester.requestFocus() }
        compose.onNodeWithTag("cast-test-episode").assertIsFocused()
        compose.onNodeWithTag("cast-test-first-actor").assertDoesNotExist()
        compose.onNodeWithTag("cast-test-episode").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("cast-test-first-actor").assertIsFocused()
                compose.onNodeWithTag("cast-test-first-actor").assertIsDisplayed()
            }.isSuccess
        }
    }
}
