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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Same Android TV emulator/viewport, one fixed home composition:
 * force a baseline draw without changing focus, then navigate with D-pad.
 *
 * Distinguishes costly software rendering from focus-triggered work.
 * This is NOT a GPU benchmark of real TCL hardware or photon latency.
 */
@RunWith(AndroidJUnit4::class)
class M445FrameBaselineTest {
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("herrin", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun p95Millis(ns: List<Long>): String {
        if (ns.isEmpty()) return "unavailable"
        val sorted = ns.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        return String.format(Locale.US, "%.2f", p95 / 1_000_000.0)
    }

    private fun persist(line: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("mkdir -p /data/local/tmp/herrin-test-evidence && " +
                "echo '$line' >> /data/local/tmp/herrin-test-evidence/remote-performance.log")
        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        fd.close()
    }

    @Test fun forcedRedrawBaselineSeparatesSoftwareRendererFromDpadWork() {
        compose.waitUntil(12_000) {
            runCatching {
                compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
                compose.onNodeWithTag("card-orbit").assertIsDisplayed()
            }.isSuccess
        }
        compose.onNodeWithTag("card-orbit")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag("card-orbit").assertIsFocused()

        val phase = AtomicInteger(0)
        val baseline = Collections.synchronizedList(mutableListOf<Long>())
        val navigation = Collections.synchronizedList(mutableListOf<Long>())
        val handlerThread = HandlerThread("HerrinM445FrameMetrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (ns > 0L) {
                if (phase.get() == 0) baseline.add(ns)
                else navigation.add(ns)
            }
        }
        compose.runOnUiThread {
            compose.activity.window.addOnFrameMetricsAvailableListener(
                listener, Handler(handlerThread.looper))
        }
        try {
            // A forced root redraw tests this software renderer's base cost
            // without switching the selected title or hero.
            repeat(24) {
                compose.runOnUiThread {
                    compose.activity.window.decorView.invalidate()
                }
                SystemClock.sleep(35)
            }
            compose.waitForIdle()
            // Drain delayed FrameMetrics callbacks before marking navigation.
            SystemClock.sleep(250)
            phase.set(1)
            repeat(12) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                    KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.waitForIdle()
                compose.onNodeWithTag("card-tide").assertIsFocused()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                    KeyEvent.KEYCODE_DPAD_LEFT)
                compose.waitForIdle()
                compose.onNodeWithTag("card-orbit").assertIsFocused()
            }
        } finally {
            compose.runOnUiThread {
                compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            handlerThread.quitSafely()
        }
        val base = baseline.toList()
        val active = navigation.toList()
        val base95 = p95Millis(base)
        val nav95 = p95Millis(active)
        val ratio = if (base95 == "unavailable" || nav95 == "unavailable" ||
            base95.toDouble() <= 0.0) "unavailable"
            else String.format(Locale.US, "%.2f", nav95.toDouble() / base95.toDouble())
        val log = "m445_redraw_frames=${base.size} redraw_p95_ms=$base95 " +
            "navigation_frames=${active.size} navigation_p95_ms=$nav95 " +
            "navigation_to_redraw_ratio=$ratio"
        Log.i("HerrinPerf", log)
        persist(log)
        assertTrue("D-pad navigation should produce compositor frames", active.isNotEmpty())
    }
}
