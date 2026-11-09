package tv.herrin.app

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real decoder + native SurfaceView geometry + screenshot border pixels, including MKV. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class M456NativeFullscreenPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun find(root: View): PlayerView? {
        if (root is PlayerView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun verify(name: String, ratio: Float) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val prefs = Preferences(inst.targetContext)
        val old = prefs.playbackFillScreen
        val file = File(inst.targetContext.cacheDir, name)
        inst.context.assets.open("aspect/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        var player: ExoPlayer? = null
        try {
            prefs.playbackFillScreen = true // Upgrade from old default must still preserve all borders.
            compose.setContent { AuthorizedPlaybackPanel(
                PlaybackMedia(android.net.Uri.fromFile(file).toString()), onPlayer = { player = it }, onBack = {}) }
            compose.waitUntil(15000) {
                var rendered = false
                compose.runOnUiThread { rendered = (player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) >= 3 }
                rendered
            }
            val bounds = IntArray(4)
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val view = requireNotNull(find(decor))
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
                assertEquals("Parent must cover the window horizontally", decor.width, view.width)
                assertEquals("No parent padding", 0, view.paddingLeft + view.paddingRight + view.paddingTop + view.paddingBottom)
                val surface = requireNotNull(view.videoSurfaceView)
                assertTrue("Actual native surface ratio", kotlin.math.abs(surface.width.toFloat() / surface.height - ratio) < .025f)
                val expectedWidth = minOf(view.width.toFloat(), view.height * ratio)
                val expectedHeight = minOf(view.height.toFloat(), view.width / ratio)
                assertEquals("Maximal uncropped width", expectedWidth, surface.width.toFloat(), 3f)
                assertEquals("Maximal uncropped height", expectedHeight, surface.height.toFloat(), 3f)
                val location = IntArray(2); surface.getLocationOnScreen(location)
                bounds[0] = location[0]; bounds[1] = location[1]; bounds[2] = surface.width; bounds[3] = surface.height
            }
            val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
            try {
                // Original fixtures have a white border and red interior. ZOOM loses these corners.
                for (x in listOf(.01f, .99f)) for (y in listOf(.01f, .99f)) {
                    val color = screen.getPixel(bounds[0] + (bounds[2] * x).toInt(), bounds[1] + (bounds[3] * y).toInt())
                    assertTrue("Decoded border must remain visible ($name, $x, $y)",
                        android.graphics.Color.red(color) > 160 && android.graphics.Color.green(color) > 160 && android.graphics.Color.blue(color) > 160)
                }
                val center = screen.getPixel(bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2)
                assertTrue("Real red video frame", android.graphics.Color.red(center) > 120 && android.graphics.Color.green(center) < 100)
                val dir = File(inst.targetContext.getExternalFilesDir(null), "aspect-evidence").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { screen.recycle() }
            inst.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/herrin-test-evidence").close()
            inst.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/herrin-test-evidence/aspect-$name.png").use {
                java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
            }
        } finally { prefs.playbackFillScreen = old; file.delete() }
    }

    @Test fun wideFrameFillsWindowWithoutCropping() = verify("wide-16x9.mp4", 16f / 9)
    @Test fun cinemaFramePreservesAllEdgesWithLetterbox() = verify("cinema-21x9.mp4", 21f / 9)
    @Test fun classicMkvPreservesAllEdgesWithPillarbox() = verify("classic-4x3.mkv", 4f / 3)
}
