package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class P0ExperienceDpadTest {
    @get:Rule val compose=createComposeRule()

    @Test fun allLineCountsMoveToCurrentOffscreenEpisodeAndBackToLastLine() {
        var count by mutableIntStateOf(1)
        compose.setContent {
            key(count) {
                val list=rememberLazyListState()
                val scope=rememberCoroutineScope()
                val lines=remember { List(count) { FocusRequester() } }
                val episode=remember { FocusRequester() }
                var last by remember { mutableIntStateOf(0) }
                val pending=remember { arrayOfNulls<Job>(1) }
                MaterialTheme {
                    LazyColumn(Modifier.width(550.dp).height(190.dp).testTag("regions"),state=list) {
                        item {
                            DetailLineRow(List(count) { "fixture-$it" to "Original line $it" },"fixture-0",true,
                                requester={lines[it]},onFocus={last=it},
                                onDown={pending[0]?.cancel(); pending[0]=scope.launch { DetailRegionFocus.move(list,episode,true) }},onSelect={})
                        }
                        items(5) { Spacer(Modifier.height(80.dp)) }
                        item {
                            TvButton("Current episode",Modifier.testTag("current").focusRequester(episode)
                                .onPreviewKeyEvent { e ->
                                    if(e.type==KeyEventType.KeyDown && e.key==Key.DirectionUp) {
                                        pending[0]?.cancel();pending[0]=scope.launch { DetailRegionFocus.move(list,lines[last],false) };true
                                    } else false
                                },true) {}
                        }
                    }
                }
            }
        }
        for (n in listOf(1,10,30,50)) {
            compose.runOnIdle { count=n };compose.waitForIdle()
            val line="line-fixture-${n-1}"
            compose.onNodeWithTag(line).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(line).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("current").assertIsFocused() }.isSuccess }
            compose.onNodeWithTag("current").assertIsDisplayed().performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag(line).assertIsFocused().assertIsDisplayed() }.isSuccess }
        }
    }

    @Test fun focusedWhitePillOverridesSelectionAndOnlyOneNavCanBeFocused() {
        val first=FocusRequester();val second=FocusRequester()
        compose.setContent {
            MaterialTheme { Row(Modifier.width(500.dp)) {
                TvNavTab("Home",Modifier.testTag("home").focusRequester(first),reduced=true,selected=true) {}
                TvNavTab("Movies",Modifier.testTag("movies").focusRequester(second),reduced=true) {}
            } }
        }
        fun color(tag:String): Color {
            val bitmap=compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return bitmap[bitmap.width/2,3]
        }
        compose.runOnIdle { second.requestFocus() };compose.waitForIdle()
        compose.onNodeWithTag("movies").assertIsFocused();compose.onNodeWithTag("home").assertIsNotFocused()
        assertEquals(Color(0xFF3A3D43).value,color("home").value)
        assertEquals(Color(0xFFF4F4F4).value,color("movies").value)
        compose.runOnIdle { first.requestFocus() };compose.waitForIdle()
        assertEquals(Color(0xFFF4F4F4).value,color("home").value)
        compose.onNodeWithTag("movies").assertIsNotFocused()
    }
}
