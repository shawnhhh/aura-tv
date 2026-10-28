package tv.herrin.app

import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertEquals
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.HttpsURLConnection

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
            runCatching {
                compose.onNodeWithTag("details").assertIsDisplayed()
                // Do not inject a remote event into the brief branded exit overlay.
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
            }.isSuccess
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
    private fun dismissEditorIme() {
        // On Android TV, performTextReplacement does not guarantee that a
        // keyboard window is open. Sending BACK unconditionally can therefore
        // leave Settings instead of dismissing an IME, making save tests flaky.
        compose.activity.runOnUiThread {
            val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= 30) compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        compose.onNodeWithTag("settings-list").assertExists()
    }
    private fun advancedSettings() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-advanced"))
        compose.onNodeWithTag("settings-advanced").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("screen-first"))
        compose.onNodeWithTag("screen-first").assertIsFocused()
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
    @Test fun navigationChangesPillsWithoutReintroducingUnderline() {
        compose.onNodeWithTag("nav-motion-track").assertDoesNotExist()
        focus("nav-首页")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("nav-电影").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("精选电影 · 演示片库").assertIsDisplayed()
    }
    @Test fun homeHeaderKeepsCompactSettingsAndExactlyTwoSourceInputs() {
        // The approved compact header has one row and no standalone source button.
        compose.onNodeWithTag("source-settings").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("设置 · 内容来源").assertIsDisplayed()
        compose.onNodeWithTag("tvbox-warehouse-url").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-live-direct-url"))
        compose.onNodeWithTag("tvbox-live-direct-url").assertIsDisplayed()
        compose.onNodeWithTag("tvbox-http-consent").assertDoesNotExist()
        compose.onNodeWithTag("tvbox-spider-consent").assertDoesNotExist()
        compose.onNodeWithTag("tvbox-import-json").assertDoesNotExist()
        compose.onNodeWithTag("config-media-url").assertDoesNotExist()
        capture("compact-settings-two-inputs")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings").assertIsDisplayed()
    }
    @Test fun compactSettingsDpadMovesThroughBothInputsAndActionsWithoutSkippingRows() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("screen-first").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("tvbox-warehouse-nav").assertIsFocused()
        if (Build.VERSION.SDK_INT >= 30) compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("tvbox-warehouse-load").assertIsFocused()
        focus("settings-source-sites")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("tvbox-live-direct-nav").assertIsFocused()
        if (Build.VERSION.SDK_INT >= 30) compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("settings-source-sites").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("tvbox-live-direct-load").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("settings-home").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("settings-advanced").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("settings-home").assertIsFocused()
        capture("settings-two-source-vertical-focus")
    }
    @Test fun compactSettingsOnlyOpenTextEditorAfterRemoteOk() {
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("tvbox-warehouse-nav")
        compose.onNodeWithTag("tvbox-warehouse-url").assertIsNotFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("tvbox-warehouse-url").assertIsFocused() }.isSuccess
        }
        compose.onNodeWithTag("tvbox-warehouse-url")
            .performTextReplacement("http://example.org/authorized.json")
        compose.onNodeWithTag("tvbox-warehouse-url")
            .assertTextContains("http://example.org/authorized.json")
    }
    @Test fun homeDpadDownMovesFromNavigationToHeroThenFirstPoster() {
        focus("nav-首页")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("play").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("card-orbit").assertIsFocused()

        focus("details")
        val before = compose.onNodeWithTag("card-orbit").fetchSemanticsNode().boundsInRoot
        focus("card-orbit")
        val after = compose.onNodeWithTag("card-orbit").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused poster width must remain stable", abs(before.width - after.width) < 0.5f)
        assertTrue("Focused poster height must remain stable", abs(before.height - after.height) < 0.5f)
        // Clickable parents merge child semantics; a film may appear in both shelves.
        compose.onAllNodesWithTag("poster-image-orbit", useUnmergedTree = true)
            .onFirst().assertExists()
        capture("poster-fixed-size-focused")
    }
    @Test fun homeDpadDownAndUpKeepTheSameColumnAcrossBothPosterShelves() {
        focus("card-orbit")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithTag("recommend-blue").assertIsFocused() }.isSuccess
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithTag("card-orbit").assertIsFocused() }.isSuccess
        }
        focus("card-tide")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithTag("recommend-return").assertIsFocused() }.isSuccess
        }
        capture("poster-cross-rail-vertical-focus")
    }
    @Test fun searchFilterDpadDownReachesSuggestionsAndResultAndUpReturns() {
        focus("search")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-filter-全部").assertIsDisplayed()
        focus("search-filter-全部")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("search-suggest-轨道").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("card-orbit").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("search-suggest-轨道").assertIsFocused()
        capture("search-vertical-focus")
    }
    @Test fun applyingTvBoxSourceReplacesDemoHomeAndPopulatesMovieTab() {
        MockWebServer().use { server ->
            server.start()
            val api = server.url("/cms").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sites":[{"name":"授权影视站","api":"$api"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"list":[{"vod_id":"remote-film-7","vod_name":"远端电影","type_name":"电影","vod_year":"2026"}]}"""))
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url")
                .performTextReplacement(server.url("/box.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(15000) {
                runCatching { compose.onNodeWithTag("vod-apply-feedback")
                    .assertTextEquals("成功") }.isSuccess
            }
            settings.performScrollToNode(hasTestTag("settings-home"))
            compose.onNodeWithTag("settings-home").performClick()
            compose.onAllNodesWithText("远端电影").onFirst().assertIsDisplayed()
            compose.onNodeWithTag("source-switch-site").assertDoesNotExist()
            focus("nav-电影"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("source-switch-site").assertDoesNotExist()
            compose.onAllNodesWithText("远端电影").onFirst().assertIsDisplayed()
            compose.onNodeWithText("蓝色边界").assertDoesNotExist()
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("settings-list")
                .performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").assertIsDisplayed()
            assertEquals(2, server.requestCount)
            capture("tvbox-applied-home")
        }
    }
    @Test fun applyingLiveSourcePopulatesLiveTabWithoutHttpToggle() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl")
                .setBody("""#EXTM3U
#EXTINF:-1 group-title="新闻",家庭新闻台
http://example.org/live/channel.m3u8
"""))
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-live-direct-url"))
            compose.onNodeWithTag("tvbox-live-direct-url")
                .performTextReplacement(server.url("/live.m3u").toString())
            settings.performScrollToNode(hasTestTag("tvbox-live-direct-load"))
            compose.onNodeWithTag("tvbox-live-direct-load").performClick()
            compose.waitUntil(15000) {
                runCatching { compose.onNodeWithTag("live-apply-feedback")
                    .assertTextEquals("成功") }.isSuccess
            }
            settings.performScrollToNode(hasTestTag("settings-home"))
            compose.onNodeWithTag("settings-home").performClick()
            focus("nav-直播"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithTag("live-fullscreen").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithTag("live-playing-name")
                .assertTextContains("家庭新闻台", substring = true)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-channel-drawer").assertIsDisplayed()
            compose.onNodeWithTag("live-drawer-channel-0").assertTextContains("家庭新闻台")
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun spiderOnlySourceDoesNotSilentlyFallBackToDemoFilms() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sites":[{"name":"第三方测试站","api":"csp_Demo"}],
                    "spider":"https://example.org/spider.jar"}"""))
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url")
                .performTextReplacement(server.url("/spider-only.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("vod-apply-feedback")
                    .assertTextEquals("未获取到影片") }.isSuccess
            }
            // A failed auto-import must show an actionable reason on the compact
            // TV settings page, not only a status hidden in Advanced Settings.
            settings.performScrollToNode(hasTestTag("compact-tvbox-status"))
            compose.onNodeWithTag("compact-tvbox-status")
                .assertTextContains("均未提供可用目录", substring = true)
            settings.performScrollToNode(hasTestTag("settings-home"))
            compose.onNodeWithTag("settings-home").performClick()
            compose.onNodeWithTag("source-empty-title").assertIsDisplayed()
            compose.onNodeWithText("蓝色边界").assertDoesNotExist()
            compose.onNodeWithTag("source-open-sites").assertDoesNotExist()
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("settings-list")
                .performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").performClick()
            compose.onNodeWithTag("site-spider-consent").assertDoesNotExist()
            compose.onNodeWithTag("site-select-0").assertExists()
            assertEquals(1, server.requestCount)
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
    @Test fun navigationUsesOnlyPillFocusWithoutExtraUnderline() {
        compose.onNodeWithTag("nav-motion-track").assertDoesNotExist()
        focus("nav-首页")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("nav-电影").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("nav-首页").assertIsFocused()
        capture("nav-no-underline")
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
        advancedSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-media-url"))
        compose.onNodeWithTag("config-media-url").performTextReplacement("http://invalid.example/test.mp4")
        dismissEditorIme()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存媒体配置"))
        activate("保存媒体配置")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存失败：链接必须是合法 HTTPS 地址"))
        compose.onNodeWithText("保存失败：链接必须是合法 HTTPS 地址").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-media-url"))
        compose.onNodeWithTag("config-media-url").performTextReplacement("https://media.example.org/authorized.mp4")
        dismissEditorIme()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存媒体配置"))
        activate("保存媒体配置")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("配置已保存；测试流只会在手动进入演示播放页时使用"))
        compose.onNodeWithText("配置已保存；测试流只会在手动进入演示播放页时使用").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("重新同步目录"))
        activate("重新同步目录")
        compose.onNodeWithText("当前使用离线演示目录").assertExists()
        capture("media-settings-saved")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-media-url"))
        compose.onNodeWithTag("config-media-url").assertTextContains("https://media.example.org/authorized.mp4")
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
        advancedSettings()
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
        advancedSettings()
        compose.onNodeWithText("目录源管理 · 最多 10 个授权 HTTPS 目录").assertExists()
        compose.onNodeWithTag("source-demo").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-catalog-url"))
        compose.onNodeWithTag("config-catalog-url").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存媒体配置"))
        compose.onNodeWithText("保存媒体配置").assertIsDisplayed()
        capture("settings-source-picker")
    }

    @Test fun tvBoxMultiwarehouseAcceptsHttpByDefaultAndNavigatesWithRemote() {
        MockWebServer().use { server ->
            server.start()
            val singleUrl = server.url("/single.json")
            val sites = buildString {
                append("""{"sites":[{"name":"影视菜单演示","api":"csp_Demo"}""")
                for (i in 1..48) append(""",{"name":"站点$i","api":"csp_Site$i"}""")
                append("]}")
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"urls":[{"name":"自建演示仓","url":"$singleUrl"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(sites))
            advancedSettings()
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url").performTextReplacement(server.url("/multi.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            // A TV IME can consume the first Center key after editing; execute the
            // button's accessible click directly for this keyboard handoff only.
            focus("tvbox-warehouse-load")
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            // HTTP is accepted by default; there is no extra enable switch.
            settings.performScrollToNode(hasTestTag("tvbox-status"))
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithTag("tvbox-status").assertTextContains("已识别 1 个仓库", substring = true) }.isSuccess
            }
            settings.performScrollToNode(hasTestTag("tvbox-entry-0"))
            focus("tvbox-entry-0"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            settings.performScrollToNode(hasTestTag("tvbox-status"))
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithTag("tvbox-status").assertTextContains("已识别单仓", substring = true) }.isSuccess
            }
            settings.performScrollToNode(hasTestTag("tvbox-site-preview"))
            compose.onNodeWithTag("tvbox-site-preview").assertTextContains("影视菜单演示", substring = true)
            settings.performScrollToNode(hasTestTag("tvbox-site-capabilities"))
            compose.onNodeWithTag("tvbox-site-capabilities").assertTextContains("49 个站点需要 Spider", substring = true)
            settings.performScrollToNode(hasTestTag("tvbox-sites-toggle"))
            compose.onNodeWithTag("tvbox-sites-toggle").performClick()
            settings.performScrollToNode(hasTestTag("tvbox-site-48"))
            compose.onNodeWithTag("tvbox-site-48").assertTextContains("站点48", substring = true)
            compose.onNodeWithTag("tvbox-site-48").performClick()
            settings.performScrollToNode(hasTestTag("tvbox-status"))
            compose.onNodeWithTag("tvbox-status").assertTextContains("Java Spider", substring = true)
            compose.onNodeWithTag("tvbox-spider-consent").assertDoesNotExist()
            assertEquals(2, server.requestCount)
            capture("settings-tvbox-multiwarehouse")
        }
    }

    @Test fun settingsOfferExplicitSourceBackupImportAndSafeDeleteFlow() {
        advancedSettings()
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
        advancedSettings()
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
        compose.onNodeWithTag("search-filter-status").assertTextContains("动漫 · 2 个结果", substring = true)
        compose.onNodeWithTag("card-night").assertExists()
        focus("search-sort")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("排序：最新优先").assertExists()
        focus("search-filter-全部")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("search-filter-status").assertTextContains("全部 · 6 个结果", substring = true)
        capture("search-filter-sort")
    }

    @Test fun diagnosticsNeverTreatRefreshHintAsMeasuredFps() {
        advancedSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("diagnostic-refresh"))
        activate("查看设备状态")
        compose.onNodeWithTag("device-status").assertTextContains("提示值不等于实际帧率", substring = true)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("poster-cache-clear"))
        compose.onNodeWithTag("poster-cache-clear").assertIsDisplayed()
        capture("settings-refresh-diagnostics")
    }

    @Test fun liveGuideIsOptionalAndConfiguredViaRemoteSettings() {
        focus("nav-直播")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("live-epg-status").assertTextContains("未配置 XMLTV", substring = true)
        key(KeyEvent.KEYCODE_BACK)
        advancedSettings()
        val settings = compose.onNodeWithTag("settings-list")
        settings.performScrollToNode(hasTestTag("config-epg-url"))
        compose.onNodeWithTag("config-epg-url").assertIsDisplayed()
        settings.performScrollToNode(hasTestTag("epg-save"))
        compose.onNodeWithTag("epg-save").assertIsDisplayed()
        capture("settings-xmltv-guide")
    }

    @Test fun liveStartsFullscreenAndDrawerSupportsGroupsFavoritesAndChannelSwitch() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val playlist = """#EXTM3U
            #EXTINF:-1 group-title="新闻",新闻一
            https://licensed.example.org/news1.m3u8
            #EXTINF:-1 group-title="体育",体育一
            https://licensed.example.org/sports.m3u8
            #EXTINF:-1 group-title="新闻",新闻二
            https://licensed.example.org/news2.m3u8
            #EXTINF:-1,独立台
            https://licensed.example.org/other.m3u8
        """.trimIndent()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl")
            .setBody(playlist))
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            Preferences(compose.activity).livePlaylistUrl = server.url("/channels.m3u").toString()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("nav-直播").assertExists() }.isSuccess
            }
            focus("nav-直播")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("live-fullscreen").assertExists() }.isSuccess
            }
            compose.onNodeWithTag("live-playing-name").assertTextEquals("新闻一")
            compose.onNodeWithTag("live-drawer-all").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-channel-drawer").assertIsDisplayed()
            compose.onNodeWithTag("live-drawer-all").assertExists()
            compose.onNodeWithTag("live-drawer-channel-0").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-drawer-all").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("live-drawer-channel-0").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("live-drawer-search-toggle").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("live-drawer-channel-0").assertIsFocused()
            compose.onNodeWithTag("live-drawer-group-1").performClick() // 体育
            compose.onNodeWithTag("live-drawer-channel-0").assertTextContains("体育一")
            compose.onNodeWithTag("live-drawer-channel-0").performClick()
            compose.onNodeWithTag("live-playing-name").assertTextEquals("体育一")
            compose.onNodeWithTag("live-channel-drawer").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-drawer-all").performClick()
            compose.onNodeWithTag("live-drawer-favorite-0").performClick()
            compose.onNodeWithTag("live-drawer-favorites").performClick()
            compose.onNodeWithTag("live-drawer-channel-0").assertTextContains("新闻一")
            compose.onNodeWithTag("live-drawer-group-1").performClick()
            compose.onNodeWithTag("live-drawer-empty").assertExists()
            key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("live-channel-drawer").assertDoesNotExist()
            compose.onNodeWithTag("live-player-focus").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-channel-drawer").assertIsDisplayed()
            compose.onNodeWithTag("live-drawer-all").assertExists()
            key(KeyEvent.KEYCODE_BACK)
            capture("live-fullscreen-left-drawer")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            server.shutdown()
        }
    }

    @Test fun liveChannelSearchRunsInsidePlaybackDrawerWithoutRefetchingPlaylist() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val playlist = """#EXTM3U
            #EXTINF:-1 tvg-id="news-main" group-title="新闻",新闻台
            https://licensed.example.org/news.m3u8
            #EXTINF:-1 tvg-id="SPORT-HD" group-title="体育",体育台
            https://licensed.example.org/sport.m3u8
            #EXTINF:-1 tvg-id="music-1" group-title="音乐",音乐台
            https://licensed.example.org/music.m3u8
        """.trimIndent()
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl")
                .setBody(playlist))
            Preferences(compose.activity).livePlaylistUrl = server.url("/licensed.m3u").toString()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("nav-直播").assertExists() }.isSuccess
            }
            focus("nav-直播")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("live-fullscreen").assertExists() }.isSuccess
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("live-drawer-search-toggle").performClick()
            compose.onNodeWithTag("live-drawer-search-input").assertIsDisplayed()
            compose.onNodeWithTag("live-drawer-search-input").performTextReplacement("SPORT")
            compose.onNodeWithTag("live-drawer-channel-0").assertTextContains("体育台")
            compose.onNodeWithTag("live-drawer-search-input").performTextReplacement("无结果")
            compose.onNodeWithTag("live-drawer-empty").assertExists()
            compose.onNodeWithTag("live-drawer-search-input").performTextReplacement("sport")
            compose.onNodeWithTag("live-drawer-channel-0").performClick()
            compose.onNodeWithTag("live-playing-name").assertTextEquals("体育台")
            assertTrue("Live search must not request another playlist", server.requestCount == 1)
            capture("live-drawer-search-and-switch")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            server.shutdown()
        }
    }
}
