package tv.herrin.app

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Real Activity + OS key injection. Focus setup is explicit, never inferred from timing. */
@RunWith(AndroidJUnit4::class)
class HerrinUiSmokeTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val evidence = object : TestWatcher() {
        override fun failed(e: Throwable?, d: Description) { capture("FAIL-${d.methodName}") }
    }
    @Before fun awaitHome() {
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithTag("details").assertIsDisplayed() }.isSuccess
        }
        compose.waitForIdle()
    }
    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
    private fun activate(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun capture(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(dir, "$label-semantics.txt").writeText(compose.onRoot().printToString())
    }
    @Test fun remoteAllDirectionsCategoriesAndVisibleFocus() {
        focus("nav-首页")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("nav-电影").assertIsFocused()
        capture("remote-focused-movies")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("nav-首页").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("精选电影 · 演示片库").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("精选首页 · 演示片库").assertExists()
        focus("card-orbit")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("card-tide").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("card-orbit").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("card-orbit").assertIsNotFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("card-orbit").assertIsFocused()
        capture("remote-focused-poster")
    }
    @Test fun posterDetailBackRestoresFocus() {
        focus("card-tide")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("演示选集").assertExists()
        compose.onNodeWithText("第 6 集").assertExists()
        capture("detail")
        activate("▶ 播放演示")
        compose.onNodeWithText("播放演示").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("演示选集").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("card-tide").assertIsFocused()
        capture("detail-back-focus")
    }
    @Test fun offlineSearchTextEmptyAndRemoteResult() {
        focus("search"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-input").performTextInput("not-a-film")
        compose.onNodeWithText("没有找到匹配影片").assertExists()
        compose.onNodeWithTag("search-input").performTextReplacement("  last ORBIT  ")
        compose.onNodeWithText("搜索结果 · 1").assertExists()
        capture("search")
        focus("card-orbit"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("演示选集").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("card-orbit").assertIsFocused()
        activate("潮汐")
        compose.onNodeWithText("搜索结果 · 1").assertExists()
        compose.onNodeWithTag("card-tide").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("search").assertIsFocused()
    }
    @Test fun favoritesPersistAndRemovingLastFavoriteRestoresNavigation() {
        focus("card-orbit"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        activate("♡ 添加收藏")
        compose.onNodeWithText("♥ 已收藏 · 取消").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        focus("nav-我的"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("我的收藏").assertExists()
        compose.onNodeWithTag("card-orbit").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("card-orbit").assertIsDisplayed()
        capture("favorites-persisted")
        focus("card-orbit"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        activate("♥ 已收藏 · 取消")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("暂无收藏，进入影片详情添加").assertExists()
        compose.onNodeWithTag("nav-我的").assertIsFocused()
        capture("favorites-empty")
    }
    @Test fun settingsPersistReducedMotion() {
        focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        activate("减少动态效果：关")
        compose.onNodeWithText("减少动态效果：开").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("减少动态效果：开").assertExists()
        capture("settings")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings").assertIsFocused()
    }
}
