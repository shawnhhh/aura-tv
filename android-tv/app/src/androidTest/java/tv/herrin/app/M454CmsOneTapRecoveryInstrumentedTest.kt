package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Real app + native Android TV catalog/navigation.
 * Only explicitly selected localhost CMS is contacted; wrong detail data
 * must not start a different film, and retry must recover without leaving
 * the player to visit an implementation/technical detail screen.
 */
@RunWith(AndroidJUnit4::class)
class M454CmsOneTapRecoveryInstrumentedTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun wrongSelectedMovieIsRejectedAndRetryUsesOnlyRealSelectedFilm() {
        MockWebServer().use { server ->
            server.start()
            val config = JSONObject().put("sites", JSONArray().put(JSONObject()
                .put("key", "licensed")
                .put("name", "授权本地 CMS")
                .put("api", server.url("/cms").toString())).toString()
            val listing = JSONObject().put("list", JSONArray().put(JSONObject()
                .put("vod_id", "film-A").put("vod_name", "我的影片 A")
                .put("type_name", "电影"))).toString()
            fun detail(vodId: String, url: String) = JSONObject().put("list",
                JSONArray().put(JSONObject().put("vod_id", vodId)
                    .put("vod_name", "我的影片")
                    .put("vod_play_from", "直连")
                    .put("vod_play_url", "正片\u0024$url"))).toString()

            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(config))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(listing))
            // A misbehaving CMS may silently ignore ids=film-A and return B.
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail("film-B", "https://192.0.2.1/wrong.mp4")))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail("film-A", "https://192.0.2.1/correct.mp4")))

            compose.waitUntil(12_000) {
                runCatching {
                    compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                    compose.onNodeWithTag("settings").assertIsDisplayed()
                }.isSuccess
            }
            compose.onNodeWithTag("settings").performClick()
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url")
                .performTextReplacement(server.url("/config.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(12_000) {
                runCatching {
                    compose.onNodeWithTag("vod-apply-feedback").assertTextEquals("已导入配置")
                }.isSuccess
            }
            assertEquals(1, server.requestCount)
            settings.performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").performClick()
            compose.onNodeWithTag("site-select-0").performClick()
            val tag = "card-tvbox-0-" + "film-A".hashCode().toUInt().toString(16)
            compose.waitUntil(12_000) {
                runCatching { compose.onNodeWithTag(tag).assertExists() }.isSuccess
            }
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(12_000) {
                runCatching {
                    compose.onNodeWithTag("player-startup-error").assertIsDisplayed()
                    compose.onNodeWithTag("player-startup-retry").assertIsDisplayed()
                }.isSuccess
            }
            assertEquals("Only one selected detail may be fetched on first click",
                3, server.requestCount)
            compose.onNodeWithTag("player-root-focus").assertDoesNotExist()

            // Retry stays on the consumer player surface and requests the
            // *same* film ID, not whatever the wrong response contained.
            compose.onNodeWithTag("player-startup-retry").performClick()
            compose.waitUntil(12_000) {
                runCatching { compose.onNodeWithTag("player-root-focus").assertExists() }.isSuccess
            }
            assertEquals("Retry only the selected CMS title", 4, server.requestCount)
            // The source, not HERRIN, supplied the accepted playable address.
        }
    }
    @Test fun cmsListingWithHttpFirstAndHttpsSecondPlaysWithoutAnotherDetailRequest() {
        MockWebServer().use { server ->
            server.start()
            val site = JSONObject().put("sites", JSONArray().put(JSONObject()
                .put("key", "licensed")
                .put("name", "授权直链源")
                .put("api", server.url("/cms").toString())).toString()
            // The CMS is deliberately allowed to return HTTP metadata, but
            // its first playback link is not accepted by the licensed
            // HTTPS-only player. The second valid line must be selected.
            val dollar = 36.toChar().toString()
            val triple = dollar.repeat(3)
            val listing = JSONObject().put("list", JSONArray().put(JSONObject()
                .put("vod_id", "film-A")
                .put("vod_name", "我的直链影片")
                .put("vod_play_from", "无效线路" + triple + "官方 HTTPS")
                .put("vod_play_url", "正片" + dollar +
                    "http://192.0.2.1/unusable.mp4" + triple +
                    "正片" + dollar + "https://192.0.2.1/valid.mp4"))).toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(site))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(listing))

            compose.waitUntil(12_000) {
                runCatching {
                    compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                    compose.onNodeWithTag("settings").assertIsDisplayed()
                }.isSuccess
            }
            compose.onNodeWithTag("settings").performClick()
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url")
                .performTextReplacement(server.url("/warehouse.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(12_000) {
                runCatching { compose.onNodeWithTag("vod-apply-feedback")
                    .assertTextEquals("已导入配置") }.isSuccess
            }
            settings.performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").performClick()
            compose.onNodeWithTag("site-select-0").performClick()
            val tag = "card-tvbox-0-" + "film-A".hashCode().toUInt().toString(16)
            compose.waitUntil(12_000) {
                runCatching { compose.onNodeWithTag(tag).assertExists() }.isSuccess
            }
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("player-root-focus").assertExists() }.isSuccess
            }
            assertEquals("Listing already contains a valid HTTPS line: do not refetch CMS details",
                2, server.requestCount)
            compose.onNodeWithTag("player-startup-error").assertDoesNotExist()
        }
    }

}
