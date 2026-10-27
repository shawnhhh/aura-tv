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
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-media-url"))
        compose.onNodeWithTag("config-media-url").performTextReplacement("http://invalid.example/test.mp4")
        key(KeyEvent.KEYCODE_BACK) // dismiss IME
        // Android TV may still route CENTER to the closing IME window for a few frames.
        // Await the real OS window hand-off, then inject an actual D-pad CENTER (no performClick).
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存媒体配置"))
        activate("保存媒体配置")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存失败：链接必须是合法 HTTPS 地址"))
        compose.onNodeWithText("保存失败：链接必须是合法 HTTPS 地址").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("config-media-url"))
        compose.onNodeWithTag("config-media-url").performTextReplacement("https://media.example.org/authorized.mp4")
        key(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
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
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("保存媒体配置"))
        compose.onNodeWithText("保存媒体配置").assertIsDisplayed()
        capture("settings-source-picker")
    }

    @Test fun tvBoxMultiwarehouseRequiresConsentThenNavigatesWithRemote() {
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
            focus("settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url").performTextReplacement(server.url("/multi.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            // A TV IME can consume the first Center key after editing; execute the
            // button's accessible click directly for this keyboard handoff only.
            focus("tvbox-warehouse-load")
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            settings.performScrollToNode(hasTestTag("tvbox-status"))
            compose.waitUntil(5000) {
                runCatching { compose.onNodeWithTag("tvbox-status").assertTextContains("只接受 HTTPS", substring = true) }.isSuccess
            }
            assertTrue(server.requestCount == 0)
            settings.performScrollToNode(hasTestTag("tvbox-http-consent"))
            focus("tvbox-http-consent")
            compose.onNodeWithTag("tvbox-http-consent").performClick()
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            focus("tvbox-warehouse-load")
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
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
            compose.onNodeWithTag("tvbox-site-48").assertTextContains("站点48")
            compose.onNodeWithTag("tvbox-site-48").performClick()
            settings.performScrollToNode(hasTestTag("tvbox-status"))
            compose.onNodeWithTag("tvbox-status").assertTextContains("没有安全执行引擎", substring = true)
            assertEquals(2, server.requestCount)
            capture("settings-tvbox-multiwarehouse")
        }
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
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
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
        focus("settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val settings = compose.onNodeWithTag("settings-list")
        settings.performScrollToNode(hasTestTag("config-epg-url"))
        compose.onNodeWithTag("config-epg-url").assertIsDisplayed()
        settings.performScrollToNode(hasTestTag("epg-save"))
        compose.onNodeWithTag("epg-save").assertIsDisplayed()
        capture("settings-xmltv-guide")
    }

    @Test fun liveGroupsAndFavoritesComposeWithRealDpadAndManualRefresh() {
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
        fun enqueue() = server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-mpegurl")
            .setBody(playlist))
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
            enqueue()
            // Settings/IME D-pad handoff is covered separately. Start this
            // test with a saved authorized URL so its subject is group focus,
            // favorites and refresh, never TV keyboard timing.
            Preferences(compose.activity).livePlaylistUrl = server.url("/channels.m3u").toString()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("nav-直播").assertExists() }.isSuccess
            }
            focus("nav-直播")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("live-filter-status")
                    .assertTextContains("4 / 4", substring = true) }.isSuccess
            }
            compose.onNodeWithTag("live-group-rail").assertExists()
            compose.onNodeWithTag("live-0").assertIsDisplayed()
            focus("live-group-all")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("live-group-0").assertIsFocused() // 新闻
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("live-group-1").assertIsFocused() // 体育
            // The white nav underline belongs only to focused top tabs.
            val track = compose.onNodeWithTag("nav-motion-track").captureToImage().toPixelMap()
            var bright = 0
            for (y in 0 until track.height) for (x in 0 until track.width) {
                val color = track[x, y]
                if (color.red > 0.74f && color.green > 0.74f && color.blue > 0.74f) bright++
            }
            assertTrue("Nav underline should fade after D-pad enters live groups", bright < 10)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("live-filter-status").assertTextContains("1 / 4", substring = true)
            focus("live-group-all")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("live-filter-status").assertTextContains("4 / 4", substring = true)
            focus("live-favorite-0") // Only 新闻一 is favorited.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            focus("live-favorites-filter")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("live-filter-status").assertTextContains("1 / 4", substring = true)
            focus("live-group-1") // Sports + favorites has no matches.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("live-filter-status").assertTextContains("0 / 4", substring = true)
            compose.onNodeWithText("当前分组暂无收藏频道，可切换「全部频道」或其他分组")
                .assertExists()
            focus("live-group-all")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            enqueue()
            focus("live-refresh")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithTag("live-status")
                    .assertTextContains("已加载 4 个授权直播频道") }.isSuccess
            }
            compose.onNodeWithTag("live-filter-status").assertTextContains("1 / 4", substring = true)
            capture("live-groups-dpad-favorites-refresh")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            server.shutdown()
        }
    }

    @Test fun liveChannelSearchIsLocalAndUpdatesTheVisibleRail() {
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
                runCatching { compose.onNodeWithTag("live-filter-status")
                    .assertTextContains("3 / 3", substring = true) }.isSuccess
            }
            focus("live-search-toggle")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("live-search-input").assertIsDisplayed()
            compose.onNodeWithTag("live-search-input").performTextReplacement("sport")
            compose.onNodeWithTag("live-filter-status")
                .assertTextContains("匹配 1 / 3", substring = true)
            compose.onNodeWithText("体育台", substring = true).assertExists()
            compose.onNodeWithTag("live-search-input").performTextReplacement("无结果")
            compose.onNodeWithTag("live-filter-status")
                .assertTextContains("匹配 0 / 3", substring = true)
            compose.onNodeWithText("未找到匹配频道，请清空关键词或调整分组、收藏筛选")
                .assertExists()
            compose.onNodeWithTag("live-search-clear").performClick()
            compose.onNodeWithTag("live-filter-status")
                .assertTextContains("3 / 3", substring = true)
            // The TV keyboard's Done action must return the next remote key
            // to the first matching native channel, not leave it in the IME.
            compose.onNodeWithTag("live-search-input").performTextReplacement("sport")
            compose.onNodeWithTag("live-search-input").performImeAction()
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithTag("live-0").assertIsFocused() }.isSuccess
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            // The first D-pad event can be swallowed while the TV IME is
            // relinquishing its native window focus after Done.
            val favoriteFocus = {
                runCatching { compose.onNodeWithTag("live-favorite-0")
                    .assertIsFocused() }.isSuccess
            }
            if (!favoriteFocus()) {
                compose.waitForIdle()
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            compose.waitUntil(5000) { favoriteFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("★ 已收藏").assertExists()
            assertTrue("Live search must not request another playlist", server.requestCount == 1)
            capture("live-search-ime-return-to-channel")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            server.shutdown()
        }
    }
}
