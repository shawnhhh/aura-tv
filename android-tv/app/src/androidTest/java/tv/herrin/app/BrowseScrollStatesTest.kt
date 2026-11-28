package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseScrollStatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun positionsSurviveFocusRecompositionAndSavedStateRestoration() {
        val restore = StateRestorationTester(compose)
        val focusFrame = mutableIntStateOf(0)
        lateinit var current: BrowseScrollStates
        restore.setContent {
            current = rememberBrowseScrollStates()
            Column {
                Text("Focus frame ${focusFrame.intValue}")
                LazyRow(Modifier.height(50.dp), state = current.rails.getValue("电影")) {
                    items(30) { Text("Movie $it", Modifier.width(80.dp)) }
                }
                Column(Modifier.height(100.dp).verticalScroll(current.pages.getValue("剧集"))) {
                    repeat(20) { Text("Series $it", Modifier.height(40.dp)) }
                }
            }
        }
        lateinit var original: BrowseScrollStates
        compose.runOnIdle {
            original = current
            runBlocking {
                current.rails.getValue("电影").scrollToItem(7,23)
                current.pages.getValue("剧集").scrollTo(140)
            }
            focusFrame.intValue++
        }
        compose.runOnIdle {
            assertSame(original,current)
            assertEquals(7,current.rails.getValue("电影").firstVisibleItemIndex)
            assertEquals(23,current.rails.getValue("电影").firstVisibleItemScrollOffset)
            assertEquals(140,current.pages.getValue("剧集").value)
            assertEquals(0,current.rails.getValue("剧集").firstVisibleItemIndex)
        }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(7,current.rails.getValue("电影").firstVisibleItemIndex)
            assertEquals(23,current.rails.getValue("电影").firstVisibleItemScrollOffset)
            assertEquals(140,current.pages.getValue("剧集").value)
        }
    }
}
