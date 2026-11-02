package tv.herrin.app

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real TV remote assertion: an HTTP 404 ends the current authorized playback
 * and exposes explicit user-driven provider selection. A second provider is
 * never requested merely because the first URI failed.
 */
@RunWith(AndroidJUnit4::class)
class M471ManualProviderFallbackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedSourceOffersRemoteSelectableOtherProviderWithoutSilentNetworkRequest() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            ActivityScenario.launch<PlaybackTestActivity>(
                Intent(instrument.targetContext, PlaybackTestActivity::class.java)
                    .putExtra("fixture", server.url("/broken.mp4").toString())
                    .putExtra("withOtherSourceAction", true)
            ).use { scenario ->
                compose.waitUntil(25_000L) {
                    runCatching {
                        compose.onNodeWithTag("player-error").assertExists()
                        compose.onNodeWithTag("player-try-other-source").assertIsDisplayed()
                    }.isSuccess
                }
                compose.onNodeWithTag("player-try-other-source")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.waitForIdle()
                compose.onNodeWithTag("player-try-other-source").assertIsFocused()
                instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                var requested = 0
                scenario.onActivity { requested = it.otherSourceSelections }
                assertEquals("Remote center must request provider search once", 1, requested)
                assertEquals("No other provider can be silently queried", 1, server.requestCount)
            }
        }
    }
}
