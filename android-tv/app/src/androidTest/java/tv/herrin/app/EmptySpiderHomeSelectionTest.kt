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
    private fun assertSelectedEmptySource() {
        try {
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("source-empty-status").assertIsDisplayed()
                .assertTextContains("Original empty-home fixture")
                .assertTextContains("首页暂未返回影片")
        }.isSuccess }
        } catch (failure: Throwable) {
            // This invocation has only the original fixture, no external provider/login.
            val state = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12).take(12_000)
            throw AssertionError("ORIGINAL_EMPTY_HOME_UI_DIAGNOSTIC\n" + state +
                "\nworker_stage=" + SpiderStartupTrace.recent(context) +
                "\nworker_failure=" + SpiderFailureTrace.recent(context), failure)
        }
        compose.onNodeWithTag("source-empty-status").assertTextContains("可继续搜索")
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
        assertSelectedEmptySource()
        activate("search")
        compose.onNodeWithTag("search-input").assertIsDisplayed()
    }
}
