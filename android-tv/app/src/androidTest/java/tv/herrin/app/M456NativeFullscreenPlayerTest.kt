package tv.herrin.app

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Android video view verification, not merely an enum unit test.
 * No real media endpoint is contacted; the local server deliberately withholds
 * the first frame to prove fill/fit does not depend on successful decoding.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class M456NativeFullscreenPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findVideoView(root: View): PlayerView? {
        if (root is PlayerView) return root
        if (root is ViewGroup) {
            for (child in 0 until root.childCount) {
                findVideoView(root.getChildAt(child))?.let { return it }
            }
        }
        return null
    }

    @Test fun actualPlayerViewFillsWithZoomAndSwitchesToFitWithoutRestart() {
        val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = prefs.playbackFillScreen
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val media = PlaybackMedia(server.url("/native-aspect.mp4").toString())
        try {
            prefs.playbackFillScreen = true
            compose.setContent {
                AuthorizedPlaybackPanel(media = media, onBack = {})
            }
            compose.waitUntil(7_000) {
                runCatching {
                    compose.onNodeWithTag("player-root-focus").assertExists()
                    compose.onNodeWithTag("transport-more").assertExists()
                }.isSuccess
            }
            // The real Android PlayerView, not a fabricated preview, must be
            // configured as center-crop and fill the native content surface.
            val firstView = arrayOfNulls<PlayerView>(1)
            compose.runOnUiThread {
                firstView[0] = findVideoView(compose.activity.window.decorView)
                val view = firstView[0] ?: error("No native Media3 PlayerView in Compose hierarchy")
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, view.resizeMode)
                assertTrue("Native PlayerView must be laid out", view.width > 0 && view.height > 0)
            }
            compose.onNodeWithTag("transport-viewport-mode").assertDoesNotExist()
            compose.onNodeWithTag("transport-more").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("transport-viewport-mode").assertExists() }.isSuccess
            }
            compose.onNodeWithTag("transport-viewport-mode").performClick()
            compose.waitForIdle()
            compose.runOnUiThread {
                val view = findVideoView(compose.activity.window.decorView)
                    ?: error("PlayerView vanished after aspect-mode switch")
                assertSame("Switching aspect ratio must not restart the player view",
                    firstView[0], view)
                assertEquals("Original picture must use FIT after user choice",
                    AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
            }
            assertFalse("User-selected FIT mode must be remembered", prefs.playbackFillScreen)
        } finally {
            prefs.playbackFillScreen = previous
            server.shutdown()
        }
    }
}
