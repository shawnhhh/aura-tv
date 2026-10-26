package tv.herrin.app

import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertTrue
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
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
        // IME dismissal is asynchronous outside Compose; a focused result must become actionable.
        if (Build.VERSION.SDK_INT >= 30) compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
        }
        if (tag.startsWith("card-") || tag == "screen-first") {
            val inputMethod = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            compose.waitUntil(5000) { !inputMethod.isAcceptingText }
        }
    }
    private fun key(code: Int) {
        RemoteTrace.event("TEST inject code=$code windowFocus=${compose.activity.hasWindowFocus()}")
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
        // PixelCopy synchronizes the rendered root before the device screenshot (not just semantics).
        compose.onRoot().captureToImage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val dir = "/data/local/tmp/herrin-test-evidence"
        shell("mkdir -p $dir")
        shell("screencap -p $dir/$label.png")
        val tree = compose.onRoot().printToString()
        val encoded = android.util.Base64.encodeToString(tree.toByteArray(), android.util.Base64.NO_WRAP)
        val chunks = encoded.chunked(2800)
        chunks.forEachIndexed { index, chunk ->
            android.util.Log.i("HerrinEvidence", "TREE $label $index/${chunks.size} $chunk")
        }
    }
    @Test fun remoteAllDirectionsCategoriesAndVisibleFocus() {
        focus("nav-首页")
        // Visual parity: both home shelves must be displayed, not simply composed off-screen.
        compose.onNodeWithText("继续观看").assertIsDisplayed()
        compose.onNodeWithText("热门推荐").assertIsDisplayed()
        compose.onNodeWithTag("card-orbit").assertExists()
        compose.onNodeWithTag("recommend-blue").assertExists()
        capture("home-two-shelves")
        val pixels = compose.onNodeWithText("HERRIN").captureToImage().toPixelMap()
        var brightPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red > 0.7f && c.green > 0.7f && c.blue > 0.7f) brightPixels++
        }
        assertTrue("Brand text must contrast with the dark hero", brightPixels > 30)
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
    @Test fun navMotionTrackGlidesToMeasuredTabAndReversesWithDpad() {
        fun trackCenter(): Float {
            val pixels = compose.onNodeWithTag("nav-motion-track").captureToImage().toPixelMap()
            var weighted = 0f
            var count = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.58f && color.green > 0.63f && color.blue > 0.70f) {
                    weighted += x
                    count++
                }
            }
            assertTrue("Focused navigation track must remain visible", count > 5)
            return weighted / count
        }
        focus("nav-首页")
        val homeX = trackCenter()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("nav-电影").assertIsFocused()
        val moviesX = trackCenter()
        assertTrue("Track must physically move with focus", moviesX > homeX + 25f)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("nav-首页").assertIsFocused()
        val returnedX = trackCenter()
        assertTrue("Reversing D-pad direction must return to the same tab", abs(returnedX - homeX) < 12f)
        capture("nav-motion-reversed")
    }
    @Test fun posterDetailBackRestoresFocus() {
        focus("card-tide")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("演示选集").assertExists()
        compose.onNodeWithText("第 6 集").assertExists()
        compose.onNodeWithText("演示选集").assertIsDisplayed()
        capture("detail")
        activate("▶ 播放演示")
        compose.onNodeWithText("播放演示").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("演示选集").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("card-tide").assertIsFocused()
        capture("detail-back-focus")
    }
    @Test fun selectedEpisodeAndLineAreNotSecondWhiteFocusPills() {
        focus("card-tide")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // Episode 1 and line A are selected; focus moves elsewhere without selection.
        focus("episode-4")
        fun bodyColor(tag: String): androidx.compose.ui.graphics.Color {
            val bitmap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return bitmap[bitmap.width / 2, bitmap.height / 4]
        }
        val selectedEpisode = bodyColor("episode-1")
        val focusedEpisode = bodyColor("episode-4")
        assertTrue("Selection must remain tinted instead of white", selectedEpisode.red < 0.65f)
        assertTrue("Only keyboard focus is white", focusedEpisode.red > 0.85f)
        focus("line-演示线路 B")
        val selectedLine = bodyColor("line-演示线路 A")
        val focusedLine = bodyColor("line-演示线路 B")
        assertTrue("Old selected line must not stay white", selectedLine.red < 0.65f)
        assertTrue("New focus must be white", focusedLine.red > 0.85f)
        capture("detail-unique-focus")
    }
    @Test fun offlineSearchTextEmptyAndRemoteResult() {
        focus("search"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-input").performTextInput("not-a-film")
        compose.onNodeWithText("没有找到匹配影片").assertExists()
        compose.onNodeWithTag("search-input").performTextReplacement("  last ORBIT  ")
        compose.onNodeWithText("搜索结果 · 1").assertExists()
        capture("search")
        focus("card-orbit")
        capture("search-result-focused")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // Wait for the Activity to finish the IME/window transition before asserting lazy detail content.
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithText("演示选集").assertExists() }.isSuccess
        }
        compose.onNodeWithText("演示选集").assertExists()
        compose.onNodeWithText("演示选集").assertIsDisplayed()
        capture("search-detail")
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
    @Test fun mediaSettingsRemoteSaveValidationSyncAndPersistence() {
        focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("config-media-url").performScrollTo().performTextReplacement("http://invalid.example/test.mp4")
        key(KeyEvent.KEYCODE_BACK) // dismiss IME
        // Android TV may still route CENTER to the closing IME window for a few frames.
        // Await the real OS window hand-off, then inject an actual D-pad CENTER (no performClick).
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        compose.onNodeWithText("保存媒体配置").performScrollTo()
        activate("保存媒体配置")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存失败：链接必须是合法 HTTPS 地址"))
        compose.onNodeWithText("保存失败：链接必须是合法 HTTPS 地址").assertIsDisplayed()
        compose.onNodeWithTag("config-media-url").performScrollTo().performTextReplacement("https://media.example.org/authorized.mp4")
        key(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        compose.onNodeWithText("保存媒体配置").performScrollTo()
        activate("保存媒体配置")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("配置已保存；测试流只会在手动进入演示播放页时使用"))
        compose.onNodeWithText("配置已保存；测试流只会在手动进入演示播放页时使用").assertIsDisplayed()
        compose.onNodeWithText("重新同步目录").performScrollTo()
        activate("重新同步目录")
        compose.onNodeWithText("当前使用离线演示目录").assertExists()
        capture("media-settings-saved")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("config-media-url").performScrollTo().assertTextContains("https://media.example.org/authorized.mp4")
        // Leave the restored editor before Back, just as a remote user moves to a control.
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("screen-first"))
        focus("screen-first")
        // Insets report hidden at animation start. Await the separate IME window's idle
        // transition, otherwise it can consume Back DOWN while only UP reaches the app.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings").assertIsFocused()
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
    @Test fun settingsExposeNativeSourcePickerWithoutReplacingExistingControls() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("目录源管理 · 最多 10 个授权 HTTPS 目录").assertExists()
        compose.onNodeWithTag("source-demo").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-catalog-url"))
        compose.onNodeWithTag("config-catalog-url").assertIsDisplayed()
        compose.onNodeWithText("保存媒体配置").assertExists()
        capture("settings-source-picker")
    }

    @Test fun settingsOfferExplicitSourceBackupImportAndSafeDeleteFlow() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val settings = compose.onNodeWithTag("settings-list")
        settings.performScrollToNode(hasTestTag("source-export"))
        compose.onNodeWithTag("source-export").assertIsDisplayed()
        settings.performScrollToNode(hasTestTag("import-catalogs-json"))
        compose.onNodeWithTag("import-catalogs-json").assertIsDisplayed()
        settings.performScrollToNode(hasTestTag("source-import"))
        compose.onNodeWithTag("source-import").assertIsDisplayed()
        settings.performScrollToNode(hasTestTag("source-export"))
        activate("复制目录列表 JSON")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("目录 JSON 已复制到剪贴板；如地址包含访问令牌，请勿公开分享"))
        compose.onNodeWithText("目录 JSON 已复制到剪贴板；如地址包含访问令牌，请勿公开分享").assertIsDisplayed()
        capture("settings-source-backup")
    }

    @Test fun liveTabExplainsAuthorizedPlaylistAndSettingsProvideItsUrl() {
        focus("nav-直播")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("live-status").assertExists()
        compose.onNodeWithTag("live-config").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-live-url"))
        compose.onNodeWithTag("config-live-url").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("live-save"))
        compose.onNodeWithTag("live-save").assertIsDisplayed()
        capture("settings-authorized-live")
    }

    @Test fun searchCategoryAndSortAreOperableByRemoteWithoutNetwork() {
        focus("search")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("search-filter-动漫")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-filter-status").assertTextContains("动漫 · 2 个结果")
        compose.onNodeWithTag("card-night").assertExists()
        focus("search-sort")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("排序：最新优先").assertExists()
        focus("search-filter-全部")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-filter-status").assertTextContains("全部 · 6 个结果")
        capture("search-filter-sort")
    }

    @Test fun diagnosticsNeverTreatRefreshHintAsMeasuredFps() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("diagnostic-refresh"))
        activate("查看设备状态")
        compose.onNodeWithTag("device-status").assertTextContains("提示值不等于实际帧率")
        compose.onNodeWithTag("poster-cache-clear").assertExists()
        capture("settings-refresh-diagnostics")
    }
}
