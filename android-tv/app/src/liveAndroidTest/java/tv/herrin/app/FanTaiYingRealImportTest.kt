package tv.herrin.app

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real server test. No fixtures, importJson, transport substitution or cache fallback. */
@RunWith(AndroidJUnit4::class)
class FanTaiYingRealImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val address = "http://www.饭太硬.net/tv"
    private val report = JSONObject().put("address", address).put("real_import_success", false)
        .put("node_count", JSONObject.NULL).put("stages", JSONArray())
        .put("abi", Build.SUPPORTED_ABIS.joinToString())
    private var stage = "http_consent"
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
            File(context.filesDir, "tvbox-config").deleteRecursively()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun passed(name: String) {
        report.getJSONArray("stages").put(JSONObject().put("stage", name).put("result", "PASS"))
    }
    private fun safe(value: String): String = value.replace(Regex("https?://[^\\s<>]+"), "[URL]")
        .replace(Regex("(?i)(token|cookie|authorization|password)\\s*[:=]\\s*\\S+"), "$1=[redacted]").take(180)

    /** Observe the unchanged production client's real responses; no fake server or interceptor. */
    private fun observedRepository(): TvBoxConfigRepository {
        val repo = TvBoxConfigRepository(context)
        val field = TvBoxConfigRepository::class.java.getDeclaredField("importClient\$delegate")
        field.isAccessible = true
        val client = (field.get(repo) as Lazy<*>).value as OkHttpClient
        val observed = client.newBuilder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                report.getJSONArray("requests").put(JSONObject().put("http_status", response.code))
            }
            override fun callFailed(call: Call, ioe: java.io.IOException) {
                report.put("transport_exception", ioe.javaClass.simpleName)
            }
        }).build()
        field.set(repo, lazy { observed })
        return repo
    }

    @Test fun realWarehouseImportSaveAndSiteSelection() {
        val started = SystemClock.elapsedRealtime()
        report.put("requests", JSONArray())
        try {
            val repo = observedRepository()
            val denied = runBlocking { repo.load(address, allowHttp = false) } as TvBoxConfig.Error
            assertTrue(denied.reason.contains("WAREHOUSE_HTTP_NOT_ALLOWED"))
            assertEquals(0, report.getJSONArray("requests").length())
            passed(stage)
            stage = "fetch_config"
            val fetchStarted = SystemClock.elapsedRealtime()
            val root = runBlocking { withTimeout(45_000) { repo.loadUserWarehouse(address) } }
            report.put("fetch_ms", SystemClock.elapsedRealtime() - fetchStarted)
            if (root is TvBoxConfig.Error) {
                report.put("import_error", safe(root.reason))
                throw AssertionError(root.reason)
            }
            passed(stage)
            stage = "identify_config"
            report.put("config_type", if (root is TvBoxConfig.Warehouses) "multi" else "single")
            val selectedAddress = if (root is TvBoxConfig.Warehouses) root.entries.first().url else address
            val selected = if (root is TvBoxConfig.Warehouses) runBlocking {
                withTimeout(45_000) { repo.loadUserWarehouse(selectedAddress) }
            } else root
            val single = selected as? TvBoxConfig.Single ?: error("No single config from selected warehouse")
            assertFalse(single.fromCache)
            report.put("node_count", single.sites.size).put("spider_count", single.sites.count { it.spiderApi != null })
                .put("has_default_spider", single.spiderJar != null)
                .put("nodes", JSONArray(single.sites.map { it.name }))
            passed(stage)
            stage = "save_config"
            val cached = runBlocking { repo.loadUserWarehouse(selectedAddress, preferCached = true) } as TvBoxConfig.Single
            assertTrue(cached.fromCache)
            assertEquals(single.sites, cached.sites)
            passed(stage)
            stage = "ui_apply"
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("details").assertIsDisplayed()
                    compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess
            }
            compose.onNodeWithTag("settings").performClick()
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url").performTextReplacement(address)
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(35_000) { Preferences(context).tvBoxWarehouseUrl == address }
            assertEquals(address, Preferences(context).tvBoxWarehouseUrl)
            passed(stage)
            stage = "ui_site_list"
            settings.performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").performClick()
            val list = compose.onNodeWithTag("site-list")
            if (root is TvBoxConfig.Warehouses) {
                list.performScrollToNode(hasTestTag("site-warehouse-0"))
                compose.onNodeWithTag("site-warehouse-0").performClick()
            }
            single.sites.forEachIndexed { index, site ->
                list.performScrollToNode(hasTestTag("site-select-$index"))
                compose.onNodeWithTag("site-select-$index").assertTextContains(site.name, substring = true)
            }
            passed(stage)
            stage = "target_nodes"
            val targets = JSONArray()
            report.put("targets", targets)
            for (name in listOf("玩偶哥哥", "奶酪")) {
                val site = single.sites.firstOrNull { it.name.contains(name) }
                val row = JSONObject().put("name", name).put("present", site != null)
                    .put("authorization", "UNKNOWN_NOT_OBSERVED")
                targets.put(row)
                if (site == null) { row.put("initialization", "NOT_LISTED"); continue }
                if (site.spiderApi != null && Build.SUPPORTED_ABIS.none { it.startsWith("arm") }) {
                    row.put("initialization", "ABI_BLOCKED_REQUIRES_ARM")
                    continue
                }
                val begin = SystemClock.elapsedRealtime()
                if (site.spiderApi != null) {
                    val bridge = TvBoxSpiderBridge(context)
                    try {
                        val result = runBlocking { withTimeout(75_000) { bridge.browse(site, single) } }
                        when (result) {
                            is SpiderResult.Ready -> row.put("initialization", "BROWSE_READY").put("film_count", result.value.size)
                            is SpiderResult.Failure -> row.put("initialization", "BROWSE_FAILED").put("reason", safe(result.message))
                        }
                    } finally { bridge.close() }
                } else if (site.apiUrl != null) {
                    val result = runBlocking { withTimeout(30_000) { TvBoxVodRepository().load(site, true) } }
                    when (result) {
                        is TvBoxVodResult.Ready -> row.put("initialization", "BROWSE_READY").put("film_count", result.films.size)
                        is TvBoxVodResult.Error -> row.put("initialization", "BROWSE_FAILED").put("reason", safe(result.reason))
                    }
                } else row.put("initialization", "UNSUPPORTED_RUNTIME")
                row.put("elapsed_ms", SystemClock.elapsedRealtime() - begin)
            }
            passed(stage)
            report.put("real_import_success", true)
        } catch (e: Throwable) {
            report.put("failure_stage", stage).put("failure_type", e.javaClass.simpleName)
            throw e
        } finally {
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(context.filesDir, "fantaiying-import.json").writeText(report.toString(2))
            Log.i("HERRIN-REAL-IMPORT", report.toString())
        }
    }
}
