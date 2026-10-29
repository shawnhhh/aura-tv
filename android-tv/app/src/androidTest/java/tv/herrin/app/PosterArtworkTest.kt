package tv.herrin.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Synthetic licensed-fixture artwork: validates both portrait and landscape poster rendering. */
@RunWith(AndroidJUnit4::class)
class PosterArtworkTest {
    @get:Rule val compose = createComposeRule()

    private fun stripedImage(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val colors = IntArray(width * height) { index ->
            if (index / width < height / 2) android.graphics.Color.RED
            else android.graphics.Color.BLUE
        }
        bitmap.setPixels(colors, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test fun realPortraitAndLandscapeArtKeepTheirFullEdgesWithinIdenticalFrames() {
        val portrait = stripedImage(64, 128)
        val landscape = stripedImage(128, 64)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = if (request.path == "/portrait.png") portrait else landscape
                return MockResponse().setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(data))
            }
        }
        server.start()
        try {
            val films = listOf(
                Film("tvbox-portrait-test", "竖幅测试", "TEST", "电影", "2026", "",
                    R.drawable.photofilm_orbit, artworkUrl = server.url("/portrait.png").toString()),
                Film("tvbox-landscape-test", "横幅测试", "TEST", "电影", "2026", "",
                    R.drawable.photofilm_tide, artworkUrl = server.url("/landscape.png").toString())
            )
            compose.setContent {
                MaterialTheme {
                    Row {
                        films.forEachIndexed { index, film ->
                            Box(Modifier.width(217.dp).height(87.dp)
                                .testTag("poster-viewport-$index")) {
                                PosterArtwork(film, Modifier.matchParentSize())
                            }
                        }
                    }
                }
            }
            compose.waitUntil(12000) {
                compose.onAllNodesWithTag("poster-remote-ready", useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 2
            }
            compose.waitForIdle()
            val a = compose.onNodeWithTag("poster-viewport-0").fetchSemanticsNode().boundsInRoot
            val b = compose.onNodeWithTag("poster-viewport-1").fetchSemanticsNode().boundsInRoot
            assertEquals(a.width, b.width, 0.5f)
            assertEquals(a.height, b.height, 0.5f)
            for (index in 0..1) {
                val image = compose.onNodeWithTag("poster-viewport-$index")
                    .captureToImage().toPixelMap()
                val middle = image.width / 2
                val top = image[middle, (image.height * 0.12f).toInt()]
                val bottom = image[middle, (image.height * 0.88f).toInt()]
                assertTrue("${if (index == 0) "Portrait" else "Landscape"} top edge must be visible",
                    top.red > top.blue * 2f)
                assertTrue("${if (index == 0) "Portrait" else "Landscape"} bottom edge must be visible",
                    bottom.blue > bottom.red * 2f)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun unavailableRemotePosterDoesNotDisplayAnUnrelatedDemoImage() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val film = Film("tvbox-missing-art", "无海报影片", "TEST", "电影", "2026", "",
                R.drawable.photofilm_orbit, artworkUrl = server.url("/unavailable.png").toString())
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.width(217.dp).height(87.dp)) {
                        PosterArtwork(film, Modifier.matchParentSize())
                    }
                }
            }
            compose.onNodeWithTag("poster-source-placeholder", useUnmergedTree = true)
                .assertExists()
            compose.onNodeWithTag("poster-remote-ready", useUnmergedTree = true)
                .assertDoesNotExist()
            assertEquals("/unavailable.png", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            server.shutdown()
        }
    }
}
