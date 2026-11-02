package tv.herrin.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs actual native mpv through surface + HTTP fixture *only* in opt-in
 * native-enabled ARM APK. Default CI skips because no native .so are bundled.
 * A passing regular Media3 emulator test must never substitute for this.
 */
@RunWith(AndroidJUnit4::class)
class M467MpvNativeFrameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeMpvOpensVideoAndRendersRealSurfacePixelsWhenInstalled() {
        assumeTrue("MPV binaries not installed (expected on ordinary emulator)",
            MpvNativeAvailability.inspect(instrumentation.targetContext).available)
        val fixture = instrumentation.context.assets.open("synthetic.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.substringBefore('?') != "/synthetic.mp4")
                        return MockResponse().setResponseCode(404)
                    val start = request.getHeader("Range")
                        ?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start !in fixture.indices)
                        return MockResponse().setResponseCode(416)
                    val response = MockResponse().setHeader("Content-Type", "video/mp4")
                        .setHeader("Accept-Ranges", "bytes")
                    if (request.getHeader("Range") != null) {
                        response.setResponseCode(206)
                        response.setHeader("Content-Range", "bytes $start-${fixture.size - 1}/${fixture.size}")
                    }
                    return response.setBody(Buffer().write(fixture, start, fixture.size - start))
                }
            }
            server.start()
            val intent = Intent(instrumentation.targetContext, MpvNativeSmokeActivity::class.java)
                .putExtra("mpvFixtureUrl", server.url("/synthetic.mp4").toString())
            ActivityScenario.launch<MpvNativeSmokeActivity>(intent).use { scenario ->
                var opened = false
                var failure: String? = null
                val deadline = System.currentTimeMillis() + 30_000
                while (!opened && failure == null && System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        opened = it.loaded.get()
                        failure = it.failure
                    }
                    if (!opened) Thread.sleep(100)
                }
                assertNull("MPV native engine failure", failure)
                assertTrue("MPV native failed to load synthetic MP4", opened)
                assertTrue("Real HTTP request missing", server.requestCount > 0)
                // FILE_LOADED alone can occur without video being rendered.
                // Capture the native Surface using Android PixelCopy.
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val copied = AtomicInteger(-1)
                val latch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val view = activity.nativeSurface() ?: error("Missing MPV surface")
                    PixelCopy.request(view, bitmap, { result ->
                        copied.set(result)
                        latch.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                assertTrue("PixelCopy never returned", latch.await(10, TimeUnit.SECONDS))
                assertEquals("MPV surface has no output image", PixelCopy.SUCCESS, copied.get())
                val pixels = IntArray(96 * 96)
                bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                assertTrue("Native frame remained black; MPV has not rendered visible pixels",
                    pixels.any { pixel -> (pixel and 0x00ffffff) != 0 })
                bitmap.recycle()
            }
        }
    }
}
