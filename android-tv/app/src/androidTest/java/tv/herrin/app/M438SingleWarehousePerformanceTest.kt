package tv.herrin.app

import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Collections
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real Android TV input on a single-warehouse 12-site/60-film local fixture. */
@RunWith(AndroidJUnit4::class)
class M438SingleWarehousePerformanceTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun remote(code: Int): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }

    @Test fun singleWarehouseLoadsAllSitesAndDpadHasNoSevereStall() {
        MockWebServer().use { server ->
            server.start()
            val sites = JSONArray().put(JSONObject()
                .put("key", "cms").put("name", "授权 CMS")
                .put("api", server.url("/cms").toString()))
            for (i in 1..11) sites.put(JSONObject()
                .put("key", "test-$i").put("name", "Spider 模拟站点 $i")
                .put("api", "csp_Test$i"))
            val config = JSONObject().put("sites", sites)
                .put("spider", "https://authorized.example.test/fixture.jar").toString()
            val films = JSONArray()
            for (i in 0 until 60) films.put(JSONObject()
                .put("vod_id", "performance-$i")
                .put("vod_name", "性能影片 $i")
                .put("vod_year", "2026")
                .put("type_name", "电影"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(config))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("list", films).toString()))
            compose.waitUntil(12_000) {
                runCatching {
                    compose.onNodeWithTag("details").assertIsDisplayed()
                    compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                }.isSuccess
            }
            compose.onNodeWithTag("settings").performClick()
            val settings = compose.onNodeWithTag("settings-list")
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-url"))
            compose.onNodeWithTag("tvbox-warehouse-url")
                .performTextReplacement(server.url("/warehouse.json").toString())
            settings.performScrollToNode(hasTestTag("tvbox-warehouse-load"))
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("tvbox-warehouse-load").performClick()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("vod-apply-feedback")
                    .assertTextEquals("已导入配置") }.isSuccess
            }
            val importMs = SystemClock.elapsedRealtime() - started
            assertEquals("Import must not run eleven third-party Spider plugins", 1, server.requestCount)
            settings.performScrollToNode(hasTestTag("settings-source-sites"))
            compose.onNodeWithTag("settings-source-sites").performClick()
            val catalogStart = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("site-select-0").performClick()
            val first = "card-tvbox-0-" + "performance-0".hashCode().toUInt().toString(16)
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag(first).assertExists() }.isSuccess
            }
            val catalogMs = SystemClock.elapsedRealtime() - catalogStart
            assertEquals("Only selected CMS is requested", 2, server.requestCount)
            compose.onNodeWithTag(first)
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(first).assertIsFocused()
            // Collect Android's compositor frame timings while the real
            // D-pad moves across the dense poster rail. Only metrics, never
            // source metadata or video, are sent to CI artifacts.
            val frameMs = Collections.synchronizedList(mutableListOf<Long>())
            val thread = HandlerThread("HerrinPerfCollector").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (ns > 0) frameMs.add(ns / 1_000_000)
            }
            compose.runOnUiThread {
                compose.activity.window.addOnFrameMetricsAvailableListener(
                    listener, Handler(thread.looper))
            }
            // Measure the actual Activity KeyEvent to Poster focus callback
            // for all sixteen moves on a 60-film rail, not only Compose idle.
            RemoteFocusLatencyProbe.start()
            val measured = try {
                val timings = List(16) { remote(KeyEvent.KEYCODE_DPAD_RIGHT) }
                timings to RemoteFocusLatencyProbe.stop()
            } finally {
                RemoteFocusLatencyProbe.stop()
                compose.runOnUiThread {
                    compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
                thread.quitSafely()
            }
            val samples = measured.first.sorted()
            val focusEvents = measured.second
            assertEquals("All 16 dense-shelf D-pad events must reach a film",
                16, focusEvents.size)
            assertEquals("Every horizontal event must focus a different film",
                16, focusEvents.map { it.filmId }.distinct().size)
            val keyToFocus = focusEvents.map { it.millis }.sorted()
            val focusP95 = java.lang.String.format(java.util.Locale.US, "%.2f",
                keyToFocus[(keyToFocus.size * 95 / 100).coerceAtMost(keyToFocus.lastIndex)])
            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)]
            val frames = frameMs.toList().sorted()
            val frameP95 = frames.takeIf { it.isNotEmpty() }
                ?.get((frames.size * 95 / 100).coerceAtMost(frames.lastIndex))
            Log.i("HerrinPerf", "single_warehouse=12_sites_60_films import_ms=$importMs " +
                "catalog_ms=$catalogMs dpad_p50_ms=" + samples[samples.size / 2] +
                " dpad_p95_ms=$p95 frame_p95_ms=" + (frameP95 ?: "unavailable") +
                " frames_over_16ms=" + frames.count { it > 16L } +
                " frames_total=" + frames.size)
            val denseMetrics = "m447_dense_dpad_events=16 focus_success=${focusEvents.size} " +
                "key_to_focus_p95_ms=$focusP95 end_to_idle_p95_ms=$p95 " +
                "frame_p95_ms=${frameP95 ?: "unavailable"} frames_total=${frames.size}"
            Log.i("HerrinPerf", denseMetrics)
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("mkdir -p /data/local/tmp/herrin-test-evidence && " +
                    "echo '$denseMetrics' >> /data/local/tmp/herrin-test-evidence/remote-performance.log")
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            fd.close()
            // Baseline (2026-10-30, Android TV API30): 199ms P95. Tighten
            // the regression budget from 1500ms to 600ms while leaving
            // headroom for shared GitHub CI emulator noise. Real hardware
            // target remains <= 100-150ms and is not implied by this gate.
            assertTrue("TV remote regression: P95=$p95 ms (limit 600ms)", p95 < 600)

            // 60 listed films deliberately omit episodes. A remote OK now
            // opens the real film DETAIL page, requests only this title's
            // CMS details, and never starts Media3 until a concrete episode.
            val detail = JSONObject().put("list", JSONArray().put(JSONObject()
                .put("vod_id", "performance-16")
                .put("vod_name", "性能影片 16")
                .put("vod_play_from", "授权模拟线路")
                .put("vod_play_url", "正片" + '
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail.toString()))
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("film-detail-list").assertExists()
                }.isSuccess
            }
            compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
            compose.waitUntil(10_000) { server.requestCount >= 3 }
            assertEquals("Only chosen film may request CMS details", 3, server.requestCount)
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("film-detail-list")
                        .performScrollToNode(hasTestTag("episode-1"))
                    compose.onNodeWithTag("episode-1").assertExists()
                }.isSuccess
            }
            compose.onNodeWithTag("episode-1").performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("player-root-focus").assertExists() }.isSuccess
            }
            assertEquals("Episode click must not fetch any unrelated title", 3, server.requestCount)
            Log.i("HerrinPerf", "m457_detail_episode_cms_sites=12 listed_films=60 " +
                "selected_detail_requests=1 episode_chosen=1 player_opened=1")
        }
    }
}

 + "https://192.0.2.1/fixture.mp4")))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail.toString()))
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("film-detail-list").assertExists()
                }.isSuccess
            }
            compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
            compose.waitUntil(10_000) { server.requestCount >= 3 }
            assertEquals("Only chosen film may request CMS details", 3, server.requestCount)
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("film-detail-list")
                        .performScrollToNode(hasTestTag("episode-1"))
                    compose.onNodeWithTag("episode-1").assertExists()
                }.isSuccess
            }
            compose.onNodeWithTag("episode-1").performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("player-root-focus").assertExists() }.isSuccess
            }
            assertEquals("Episode click must not fetch any unrelated title", 3, server.requestCount)
            Log.i("HerrinPerf", "m457_detail_episode_cms_sites=12 listed_films=60 " +
                "selected_detail_requests=1 episode_chosen=1 player_opened=1")
        }
    }
}

