package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP responses drive Media3's listener; no synthetic success is reported. */
@RunWith(AndroidJUnit4::class)
class M467PlaybackRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun goneResultRefreshesOnceAndBudgetSurvivesPlayerReplacement() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(410)
            }
            server.start()
            val refreshEpoch = mutableIntStateOf(0)
            val budget = FongMiPlaybackRecovery.Session()
            compose.setContent {
                key(refreshEpoch.intValue) {
                    AuthorizedPlaybackPanel(
                        media = PlaybackMedia("http://127.0.0.1:${server.port}/expired.mp4"),
                        recoverySession = budget,
                        onRefreshSource = { refreshEpoch.intValue++ },
                        onBack = {})
                }
            }
            compose.waitUntil(25_000) {
                runCatching {
                    compose.onNodeWithTag("player-error").assertTextContains("HTTP 410", substring = true)
                }.isSuccess
            }
            compose.runOnIdle { assertEquals("Exactly one playerContent refresh", 1, refreshEpoch.intValue) }
            // Both player generations sent a real request, then automatic recovery stopped.
            org.junit.Assert.assertTrue(server.requestCount >= 2)
        }
    }

    @Test fun forbiddenMediaNeverRefreshesOrRequestsProviderBackup() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
            }
            server.start()
            var refreshes = 0
            compose.setContent {
                AuthorizedPlaybackPanel(
                    media = PlaybackMedia("http://127.0.0.1:${server.port}/denied.mp4",
                        alternateUris = listOf("http://127.0.0.1:${server.port}/backup.mp4")),
                    onRefreshSource = { refreshes++ }, onBack = {})
            }
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNodeWithTag("player-error").assertTextContains("HTTP 403", substring = true)
                }.isSuccess
            }
            compose.runOnIdle { assertEquals(0, refreshes) }
            repeat(server.requestCount) {
                assertEquals("/denied.mp4", server.takeRequest().path)
            }
        }
    }

    @Test fun exitingPlaybackClosesOldProxyBeforeImmediateReopenReturnsMediaBytes() = kotlinx.coroutines.runBlocking {
        val bridge = TvBoxSpiderBridge(androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext)
        try {
            val original = bridge.probeRemoteProxyForInstrumentation() as SpiderResult.Ready
            org.junit.Assert.assertTrue(SpiderProxyPlayback.ping(original.value))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                bridge.releasePlayback()
            }
            // The next operation queues behind the release barrier, without a
            // delay that could hide Android's immediate rebind/service reuse race.
            val reopened = bridge.probeRemoteProxyForInstrumentation() as SpiderResult.Ready
            bridge.awaitPlaybackReleaseForInstrumentation()
            org.junit.Assert.assertFalse("Old token/socket must have been closed", SpiderProxyPlayback.ping(original.value))
            org.junit.Assert.assertNotEquals(original.value, reopened.value)
            val connection = java.net.URL(reopened.value).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 3_000
                assertEquals(200, connection.responseCode)
                org.junit.Assert.assertArrayEquals(byteArrayOf(8, 9, 10), connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
        } finally { bridge.close() }
    }

}
