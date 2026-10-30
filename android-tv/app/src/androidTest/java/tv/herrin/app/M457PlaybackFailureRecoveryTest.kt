package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            var lineChoices = 0
            var episodeChoices = 0
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = PlaybackMedia(server.url("/missing-film.mp4").toString()),
                    onLineSelection = { lineChoices++ },
                    onEpisodeSelection = { episodeChoices++ },
                    onBack = {}
                )
            }
            compose.waitUntil(15_000) {
                runCatching {
                    compose.onNodeWithTag("player-error").assertExists()
                    compose.onNodeWithTag("player-error-change-line").assertExists()
                    compose.onNodeWithTag("player-error-change-episode").assertExists()
                }.isSuccess
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
