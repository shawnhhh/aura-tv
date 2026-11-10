package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.ExternalResource
import java.io.File
import java.security.MessageDigest

/** Original DEX + real MainActivity/worker; no media or real-provider verdict. */
class EmptySpiderHomeSelectionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val server = MockWebServer()
    private lateinit var jarSpec: String
    private lateinit var cachedJar: File
    private var inputUsed = false
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() {
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
            val bytes = inst.context.assets.open("m467-spider.jar").use { it.readBytes() }
            val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            jarSpec = "https://example.invalid/original-empty-home.jar;md5;$md5"
            cachedJar = File(File(context.codeCacheDir, "herrin-spider").apply { mkdirs() }, "$md5.jar")
            cachedJar.delete(); cachedJar.writeBytes(bytes); check(cachedJar.setReadOnly())
            val config = JSONObject().put("spider", jarSpec).put("sites", org.json.JSONArray().put(
                JSONObject().put("key", "empty-home-fixture").put("name", "Original empty-home fixture")
                    .put("api", "csp_Fixture").put("ext", "empty-home")))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(config.toString()))
            // A recreated MainActivity refreshes metadata after restoring its cache.
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(config.toString()))
            server.start()
        }
        override fun after() {
            server.shutdown()
            cachedJar.delete()
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).assertIsFocused()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun awaitIme(shown: Boolean) {
        compose.waitUntil(5_000) {
            val state = inst.uiAutomation.executeShellCommand("dumpsys input_method").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
            }.substringAfter("Input method service state for ", "")
            state.contains("mInputViewStarted=$shown") && state.contains("mWindowVisible=$shown")
        }
    }
    @After fun dismissOwnedInput() {
        if (!inputUsed) return
        runCatching { compose.onNodeWithTag("search").performSemanticsAction(SemanticsActions.RequestFocus) }
        compose.runOnUiThread {
            compose.activity.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        awaitIme(false)
    }
    private fun importOriginalFixture() {
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithTag("settings").assertIsDisplayed()
            compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
        }.isSuccess }
        activate("settings")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-warehouse-url"))
        compose.onNodeWithTag("tvbox-warehouse-url").performTextReplacement(server.url("/original.json").toString())
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-warehouse-load"))
        activate("tvbox-warehouse-load")
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("vod-apply-feedback").assertTextEquals("已导入配置")
        }.isSuccess }
    }
    private fun assertSelectedEmptySource(advanced: Boolean = false) {
        val expected = "TVBox " + (if (advanced) "Spider " else "") +
            "Original empty-home fixture · 0 部；该影视点首页暂未返回影片，可继续搜索或检查来源授权"
        try {
            compose.waitUntil(15_000) { runCatching {
                compose.onNodeWithTag("source-empty-status").assertIsDisplayed().assertTextEquals(expected)
            }.isSuccess }
        } catch (failure: Throwable) {
            // This invocation has only the original fixture, no external provider/login.
            val state = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12).take(12_000)
            throw AssertionError("ORIGINAL_EMPTY_HOME_UI_DIAGNOSTIC\n" + state +
                "\nworker_stage=" + SpiderStartupTrace.recent(context) +
                "\nworker_failure=" + SpiderFailureTrace.recent(context), failure)
        }
        compose.onNodeWithTag("source-empty-status").assertTextEquals(expected)
        compose.onNodeWithTag("details").assertDoesNotExist()
        compose.onNodeWithTag("play").assertDoesNotExist()
        val prefs = Preferences(context)
        // Verify persisted execution consent belongs to this exact selected JAR/root.
        assertEquals(jarSpec, prefs.tvBoxTrustedSpiderJarSpec)
        assertEquals(server.url("/original.json").toString(), prefs.tvBoxTrustedSpiderRootUrl)
        assertEquals(prefs.tvBoxWarehouseUrl, prefs.tvBoxLastWorkingRootUrl)
        assertTrue(prefs.tvBoxLastWorkingSiteId.isNotBlank())
        assertEquals(prefs.tvBoxLastWorkingSiteId, prefs.tvBoxPreferredSiteId)
    }
    @Test fun sitePickerRetainsEmptyInitializedSourceAndRestoresItOnRecreate() {
        importOriginalFixture()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-source-sites"))
        activate("settings-source-sites")
        compose.onNodeWithTag("site-list").performScrollToNode(hasTestTag("site-select-0"))
        activate("site-select-0")
        assertSelectedEmptySource()
        activate("search")
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertSelectedEmptySource()
        // Reopening uses only the persisted selected site, never a default provider.
        compose.activityRule.scenario.recreate()
        assertSelectedEmptySource()
    }
    @Test fun advancedPreviewRetainsEmptyInitializedSource() {
        importOriginalFixture()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-advanced"))
        activate("settings-advanced")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-spider-0"))
        activate("tvbox-spider-0")
        assertSelectedEmptySource(advanced = true)
        activate("search")
        compose.onNodeWithTag("search-input").assertIsDisplayed()
    }

    @Test fun selectedEmptyHomeCanSearchAndOpenItsRealDetailsWithoutTmdbToken() {
        importOriginalFixture()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-source-sites"))
        activate("settings-source-sites")
        compose.onNodeWithTag("site-list").performScrollToNode(hasTestTag("site-select-0"))
        activate("site-select-0")
        assertSelectedEmptySource()
        assertEquals("", Preferences(context).tmdbReadToken)
        activate("search")
        inputUsed = true
        compose.onNodeWithTag("search-input").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        awaitIme(true)
        compose.onNodeWithTag("search-input").performTextReplacement("Original synthetic fixture")
        val resultCard = SemanticsMatcher("actual result card") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("card-")
        } and hasText("Original synthetic fixture") and hasClickAction()
        compose.waitUntil(10_000) { runCatching {
            compose.onNode(resultCard).assertExists()
        }.isSuccess }
        val result = compose.onNode(resultCard)
        result.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnUiThread {
            compose.activity.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        compose.waitUntil(5_000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        awaitIme(false)
        result.performSemanticsAction(SemanticsActions.RequestFocus)
        result.assertIsFocused()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithTag("film-detail-list").assertIsDisplayed()
        }.isSuccess }
        compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("episode-2"))
        compose.onNodeWithTag("episode-2").assertIsDisplayed()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithTag("search-input").assertIsDisplayed()
                .assertTextContains("Original synthetic fixture", substring = true)
        }.isSuccess }
    }
}
