package tv.herrin.app

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Local demo films + real Android key dispatch and Compose focus callbacks.
 * key_to_focus measures focus CALLBACK latency, not physical pixel scan-out.
 */
@RunWith(AndroidJUnit4::class)
class M442RemoteBurstPerformanceTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun home() {
        compose.waitUntil(12_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("card-orbit").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    private fun remote(code: Int): Long {
        val started = SystemClock.elapsedRealtimeNanos()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
    }

    private fun awaitFocus(tag: String) {
        compose.waitUntil(4_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess
        }
    }

    private fun p95Long(sorted: List<Long>): Long =
        sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]

    private fun p95Ms(samples: List<RemoteFocusLatencyProbe.Sample>): String {
        val values = samples.map { it.millis }.sorted()
        return String.format(Locale.US, "%.2f",
            values[(values.size * 95 / 100).coerceAtMost(values.lastIndex)])
    }

    @Test fun thirtyTwoVerticalKeyTransitionsKeepTheLatestShelfAndColumn() {
        home()
        focus("card-orbit")
        val endToIdleMs = ArrayList<Long>()
        RemoteFocusLatencyProbe.start()
        val samples = try {
            repeat(16) {
                endToIdleMs.add(remote(KeyEvent.KEYCODE_DPAD_DOWN))
                awaitFocus("recommend-blue")
                endToIdleMs.add(remote(KeyEvent.KEYCODE_DPAD_UP))
                awaitFocus("card-orbit")
            }
            RemoteFocusLatencyProbe.stop()
        } finally {
            RemoteFocusLatencyProbe.stop()
        }
        assertEquals("Every vertical D-pad press must produce one focused poster",
            32, samples.size)
        samples.forEachIndexed { i, sample ->
            assertEquals("Vertical focus should retain the expected column at key $i",
                if (i % 2 == 0) "blue" else "orbit", sample.filmId)
        }
        val p95EndToIdle = p95Long(endToIdleMs.sorted())
        val keyToFocusP95 = p95Ms(samples)
        Log.i("HerrinPerf", "m443_vertical_dpad_events=32 focus_success=${samples.size} " +
            "key_to_focus_p95_ms=$keyToFocusP95 " +
            "end_to_idle_p95_ms=$p95EndToIdle")
        assertTrue("Vertical focus severe idle stall P95=$p95EndToIdle ms",
            p95EndToIdle < 1_000)
        assertTrue("Vertical focus callback latency P95=$keyToFocusP95 ms",
            keyToFocusP95.toDouble() < 750.0)
    }

    @Test fun fortyHorizontalReversalKeysRetainPosterAndMeasureFrameP95() {
        home()
        focus("card-orbit")
        val frameNanos = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("HerrinBurstFrames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) frameNanos.add(duration)
        }
        compose.runOnUiThread {
            compose.activity.window.addOnFrameMetricsAvailableListener(
                listener, Handler(thread.looper))
        }
        val endToIdleMs = ArrayList<Long>()
        RemoteFocusLatencyProbe.start()
        val samples = try {
            repeat(20) {
                endToIdleMs.add(remote(KeyEvent.KEYCODE_DPAD_RIGHT))
                awaitFocus("card-tide")
                endToIdleMs.add(remote(KeyEvent.KEYCODE_DPAD_LEFT))
                awaitFocus("card-orbit")
            }
            RemoteFocusLatencyProbe.stop()
        } finally {
            RemoteFocusLatencyProbe.stop()
            compose.runOnUiThread {
                compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            thread.quitSafely()
        }
        assertEquals("Every horizontal D-pad press must produce one focused poster",
            40, samples.size)
        samples.forEachIndexed { i, sample ->
            assertEquals("Horizontal focus should reach exactly the requested poster",
                if (i % 2 == 0) "tide" else "orbit", sample.filmId)
        }
        val p95EndToIdle = p95Long(endToIdleMs.sorted())
        val keyToFocusP95 = p95Ms(samples)
        val frames = frameNanos.toList().sorted()
        val frameP95Ms = frames.takeIf { it.isNotEmpty() }?.let {
            String.format(Locale.US, "%.2f", p95Long(it) / 1_000_000.0)
        } ?: "unavailable"
        // 60Hz frame budget = 16.67ms, not the rounded integer 16ms.
        val longFrames = frames.count { it > 16_666_667L }
        val severeFrames = frames.count { it > 33_333_334L }
        val longFramePct = if (frames.isEmpty()) 0.0 else 100.0 * longFrames / frames.size
        val longFramePctText = String.format(Locale.US, "%.2f", longFramePct)
        Log.i("HerrinPerf", "m443_horizontal_dpad_events=40 focus_success=${samples.size} " +
            "key_to_focus_p95_ms=$keyToFocusP95 end_to_idle_p95_ms=$p95EndToIdle " +
            "frame_p95_ms=$frameP95Ms frames_total=${frames.size} " +
            "frames_over_16_67ms=$longFrames frames_over_33_33ms=$severeFrames " +
            "long_frame_pct=$longFramePctText")
        assertTrue("Horizontal focus severe idle stall P95=$p95EndToIdle ms",
            p95EndToIdle < 1_000)
        assertTrue("Horizontal focus callback latency P95=$keyToFocusP95 ms",
            keyToFocusP95.toDouble() < 750.0)
        assertTrue("FrameMetrics callback should deliver compositor samples", frames.isNotEmpty())
        compose.onNodeWithTag("card-orbit").assertIsFocused()
    }
}
