package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 failure: a broken provider URL must not trap the remote at Retry. */
@RunWith(AndroidJUnit4::class)
class M457PlaybackFailureRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedNetworkMediaOffersEpisodeAndLineChoicesWithoutAutoPlayback() {
        MockWebServer().use { server ->
            // Media3 may ask the server more than once (including HTTP retries).
            // A single queued 404 would leave later requests blocked waiting for
            // a response, masking the real final-error UI behind a timeout.
            // Every request must receive an immediate, deterministic 404.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404)
            }
            server.start()
            // Use loopback directly: MockWebServer.url() may perform DNS lookup
            // on some Android API levels and trip StrictMode on the UI thread.
            // This still sends a real HTTP request to the running local server.
            val failingMediaUrl = "http://127.0.0.1:${server.port}/missing-film.mp4"
            var lineChoices = 0
            var episodeChoices = 0
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = PlaybackMedia(failingMediaUrl),
                    onLineSelection = { lineChoices++ },
                    onEpisodeSelection = { episodeChoices++ },
                    onBack = {}
                )
            }
            // Distinguish an absent Media3 HTTP request from a rendered-player
            // failure. The provider must receive the deliberately bad request.
            compose.waitUntil(15_000) { server.requestCount > 0 }
            try {
                compose.waitUntil(20_000) {
                    runCatching {
                        compose.onNodeWithTag("player-error")
                            .assertTextContains("HTTP 404", substring = true)
                        compose.onNodeWithTag("player-error-change-line").assertExists()
                        compose.onNodeWithTag("player-error-change-episode").assertExists()
                    }.isSuccess
                }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("HTTP 404 reached server (requests=" +
                    server.requestCount + ") but recovery controls were absent: " +
                    compose.onRoot().printToString().take(2200), error)
            }
            compose.onNodeWithTag("player-error-change-line").performClick()
            compose.onNodeWithTag("player-error-change-episode").performClick()
            compose.runOnIdle {
                assertEquals(1, lineChoices)
                assertEquals(1, episodeChoices)
            }
        }
    }
}
