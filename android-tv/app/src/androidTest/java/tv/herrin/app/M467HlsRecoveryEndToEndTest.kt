package tv.herrin.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real HLS master/child/segment transport; the first segment socket is interrupted. */
@RunWith(AndroidJUnit4::class)
class M467HlsRecoveryEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun interruptedSegmentReloadsAndProducesFramesThroughProductionMediaSource() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val attempts = AtomicInteger()
        val assets = inst.context.assets
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/master.m3u8" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(assets.open("m467/master.m3u8").bufferedReader().use { it.readText() })
                    request.path == "/child.m3u8" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(assets.open("m467/child.m3u8").bufferedReader().use { it.readText() })
                    request.path == "/segment-00.ts" -> {
                        val response = MockResponse().setHeader("Content-Type", "video/mp2t")
                            .setBody(Buffer().write(assets.open("m467/segment-00.ts").use { it.readBytes() }))
                        if (attempts.incrementAndGet() == 1)
                            response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        response
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("fixture", server.url("/master.m3u8").toString())
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                compose.waitUntil(40_000) {
                    var frames = 0
                    scenario.onActivity { frames = it.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                    frames > 3 && attempts.get() > 1
                }
                assertTrue("A broken segment must be requested again", attempts.get() > 1)
            }
        }
    }
}
