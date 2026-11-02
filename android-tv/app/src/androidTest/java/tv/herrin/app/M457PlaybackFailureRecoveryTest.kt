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
            var freshSourceRequests = 0
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = PlaybackMedia(failingMediaUrl),
                    onLineSelection = { lineChoices++ },
                    onEpisodeSelection = { episodeChoices++ },
                    onRefreshSource = { freshSourceRequests++ },
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
                        compose.onNodeWithTag("player-error")
                            .assertTextContains("ERROR_CODE_IO_BAD_HTTP_STATUS", substring = true)
                        compose.onNodeWithTag("player-error")
                            .assertTextContains("失败阶段：Media3", substring = true)
                        compose.onNodeWithTag("player-error-change-line").assertExists()
                        compose.onNodeWithTag("player-error-change-episode").assertExists()
                        compose.onNodeWithTag("player-refresh-source").assertExists()
                    }.isSuccess
                }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("HTTP 404 reached server (requests=" +
                    server.requestCount + ") but recovery controls were absent: " +
                    compose.onRoot().printToString().take(2200), error)
            }
            compose.onNodeWithTag("player-error-change-line").performClick()
            compose.onNodeWithTag("player-error-change-episode").performClick()
            compose.onNodeWithTag("player-refresh-source").performClick()
            compose.runOnIdle {
                assertEquals(1, lineChoices)
                assertEquals(1, episodeChoices)
                assertEquals(1, freshSourceRequests)
            }
        }
    }
    @Test fun parserFailureBeforeMedia3DisplaysObservedHttpWithoutExposingPrivateUrl() {
        compose.setContent {
            PlayerStartupSurface(
                loading = false,
                offlineDemonstration = false,
                cmsFailed = false,
                retryAvailable = true,
                reducedMotion = true,
                failureHint = "请检查解析器授权后重试",
                failureDiagnostic = PlaybackErrorReport.source("JSON",
                    "JSON 解析接口 HTTP 响应异常（HTTP 403）；Cookie=PRIVATE"),
                onRetry = {},
                onBack = {}
            )
        }
        compose.onNodeWithTag("player-startup-diagnostic")
            .assertTextContains("SOURCE_HTTP_403", substring = true)
        compose.onNodeWithTag("player-startup-diagnostic")
            .assertTextContains("HTTP 403", substring = true)
        compose.onNodeWithTag("player-startup-retry").assertExists()
        compose.onNodeWithTag("player-startup-back").assertExists()
    }

    @Test fun preDecoderCmsFailureCanSelectOtherAuthorizedProvider() {
        var fallbackCount = 0
        compose.setContent {
            PlayerStartupSurface(
                loading = false, offlineDemonstration = false,
                cmsFailed = true, retryAvailable = true,
                reducedMotion = true,
                failureDiagnostic = PlaybackErrorReport.source("CMS", "HTTP 404"),
                onRetry = {}, onBack = {},
                onTryOtherSource = { fallbackCount++ }
            )
        }
        compose.onNodeWithTag("player-startup-error").assertIsDisplayed()
        compose.onNodeWithTag("player-startup-other-source").assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, fallbackCount) }
    }

    @Test fun loadingAndOfflineDemoNeverExposeSourceSwitch() {
        val loading = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            PlayerStartupSurface(loading = loading.value, offlineDemonstration = !loading.value,
                cmsFailed = false, retryAvailable = false,
                reducedMotion = true, onRetry = {}, onBack = {},
                onTryOtherSource = {})
        }
        compose.onNodeWithTag("player-startup-other-source").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("player-demo-status").assertIsDisplayed()
        compose.onNodeWithTag("player-startup-other-source").assertDoesNotExist()
    }
}
