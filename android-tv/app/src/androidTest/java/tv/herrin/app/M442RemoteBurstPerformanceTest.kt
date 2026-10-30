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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Production MainActivity, real Android TV remote key injection and compositor
 * frames. Local demo artwork avoids conflating D-pad latency with CDN/network.
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

    @Test fun thirtyTwoVerticalKeyTransitionsKeepTheLatestShelfAndColumn() {
        home()
        focus("card-orbit")
        val durations = ArrayList<Long>()
        repeat(16) {
            durations.add(remote(KeyEvent.KEYCODE_DPAD_DOWN))
            awaitFocus("recommend-blue")
            durations.add(remote(KeyEvent.KEYCODE_DPAD_UP))
            awaitFocus("card-orbit")
        }
        val sorted = durations.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        Log.i("HerrinPerf", "m442_vertical_dpad_events=${sorted.size} " +
            "p50_ms=${sorted[sorted.size / 2]} p95_ms=$p95 lost_focus=0")
        // A CI emulator is NOT a TV hardware latency benchmark. Catch
        // multi-second regressions; report real measurements separately.
        assertTrue("Vertical focus severe stall P95=$p95 ms", p95 < 1_000)
    }

    @Test fun fortyHorizontalReversalKeysRetainPosterAndMeasureFrameP95() {
        home()
        focus("card-orbit")
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("HerrinBurstFrames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) frames.add(duration / 1_000_000)
        }
        compose.runOnUiThread {
            compose.activity.window.addOnFrameMetricsAvailableListener(
                listener, Handler(thread.looper))
        }
        val durations = try {
            ArrayList<Long>().apply {
                repeat(20) {
                    add(remote(KeyEvent.KEYCODE_DPAD_RIGHT))
                    awaitFocus("card-tide")
                    add(remote(KeyEvent.KEYCODE_DPAD_LEFT))
                    awaitFocus("card-orbit")
                }
            }
        } finally {
            compose.runOnUiThread {
                compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            thread.quitSafely()
        }
        val sorted = durations.sorted()
        val frameSorted = frames.toList().sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        val frameP95 = frameSorted.takeIf { it.isNotEmpty() }?.let {
            it[(it.size * 95 / 100).coerceAtMost(it.lastIndex)]
        }
        Log.i("HerrinPerf", "m442_horizontal_dpad_events=${sorted.size} " +
            "p50_ms=${sorted[sorted.size / 2]} p95_ms=$p95 " +
            "frame_p95_ms=${frameP95 ?: "unavailable"} " +
            "frames_over_16ms=${frameSorted.count { it > 16L }} " +
            "frames_total=${frameSorted.size} lost_focus=0")
        assertTrue("Horizontal focus severe stall P95=$p95 ms", p95 < 1_000)
        compose.onNodeWithTag("card-orbit").assertIsFocused()
    }
}
