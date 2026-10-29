package tv.herrin.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import org.junit.Assert.assertTrue
import java.io.FileInputStream

/**
 * Tests the *actual Android TV compositor screenshot*, not only decoder counters.
 *
 * Always run this on bundled ORIGINAL synthetic video, never on user/provider
 * content. The CI publishes screenshots into HERRIN-emulator-evidence.
 */
internal object TvVisibleFrameEvidence {
    private data class Appearance(val illuminated: Int, val colored: Int, val unique: Int) {
        fun plausible(): Boolean = illuminated >= 18 && colored >= 8 && unique >= 5
    }

    fun verify(inst: Instrumentation, label: String) {
        require(label.matches(Regex("[a-z0-9-]{1,48}")))
        var strongest = Appearance(0, 0, 0)
        var visible = false
        // SurfaceView is composed asynchronously; don't equate the first
        // decoder output buffer with an immediately visible display frame.
        for (attempt in 0 until 8) {
            if (attempt > 0) SystemClock.sleep(350)
            val bitmap = inst.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                try {
                    val observed = sampleCenter(bitmap)
                    if (observed.illuminated + observed.colored >
                        strongest.illuminated + strongest.colored) strongest = observed
                    if (observed.plausible()) visible = true
                } finally {
                    bitmap.recycle()
                }
            }
            if (visible) break
        }
        // Shell-owned evidence survives instrumentation teardown and can be
        // pulled by android-tv/scripts/emulator_ci.sh after any test result.
        val cmd = "mkdir -p /data/local/tmp/herrin-test-evidence && " +
            "screencap -p /data/local/tmp/herrin-test-evidence/$label.png"
        runCatching {
            inst.uiAutomation.executeShellCommand(cmd).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stdout ->
                    val buffer = ByteArray(1024)
                    while (stdout.read(buffer) >= 0) Unit
                }
            }
        }
        assertTrue(
            "Media3 produced frames but Android TV screen may be blank: " +
                "center illuminated=${strongest.illuminated}, " +
                "colored=${strongest.colored}, colors=${strongest.unique}; " +
                "inspect HERRIN-emulator-evidence/$label.png",
            visible
        )
    }

    private fun sampleCenter(bitmap: Bitmap): Appearance {
        if (bitmap.width < 40 || bitmap.height < 40) return Appearance(0, 0, 0)
        var illuminated = 0
        var colored = 0
        val colors = HashSet<Int>()
        // 16 x 12 samples in the unobscured upper-middle video surface.
        // Exclude both the top diagnostics and bottom transport/danmaku controls:
        // their colored buttons must never qualify a black video as visible.
        for (y in 0 until 12) {
            for (x in 0 until 16) {
                val px = bitmap.getPixel(
                    (bitmap.width * (0.30 + (x + 0.5) * 0.40 / 16)).toInt()
                        .coerceIn(0, bitmap.width - 1),
                    (bitmap.height * (0.22 + (y + 0.5) * 0.28 / 12)).toInt()
                        .coerceIn(0, bitmap.height - 1)
                )
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val high = maxOf(r, g, b)
                val low = minOf(r, g, b)
                if (high > 42) illuminated++
                if (high > 52 && high - low >= 22) colored++
                if (high > 42) colors.add(((r / 32) shl 8) or ((g / 32) shl 4) or (b / 32))
            }
        }
        return Appearance(illuminated, colored, colors.size)
    }
}
