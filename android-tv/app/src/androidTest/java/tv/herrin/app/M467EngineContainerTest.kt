package tv.herrin.app

import android.content.Intent
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real Media3 decoders/HTTP requests. Synthetic media provides no TCL/provider claim. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class M467EngineContainerTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun play(path: String, caption: String? = null, verify: (PlaybackTestActivity) -> Boolean) {
        val served = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val asset = request.requestUrl!!.encodedPath.removePrefix("/")
                if (asset.contains("..")) return MockResponse().setResponseCode(400)
                val data = try { inst.context.assets.open(asset).use { it.readBytes() } }
                    catch (_: Exception) { return MockResponse().setResponseCode(404) }
                served.add(asset)
                val mime = when {
                    asset.endsWith("m3u8") -> "application/vnd.apple.mpegurl"
                    asset.endsWith("mpd") -> "application/dash+xml"
                    asset.endsWith("ts") -> "video/mp2t"
                    asset.endsWith("vtt") -> "text/vtt"
                    else -> "application/octet-stream"
                }
                val range = request.getHeader("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in data.indices) return MockResponse().setResponseCode(416)
                return MockResponse().setHeader("Content-Type", mime)
                    .setHeader("Accept-Ranges", "bytes")
                    .apply {
                        if (range != null) {
                            setResponseCode(206)
                            setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                        }
                    }.setBody(Buffer().write(data, start, data.size - start))
            }
        }
        server.start()
        try {
            val intent = Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("fixture", server.url("/$path").toString())
            if (caption != null) intent.putExtra("subtitle", server.url("/m467/synthetic.$caption").toString())
            ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                val deadline = System.currentTimeMillis() + 20_000
                var ready = false
                var errorCode: String? = null
                while (!ready && errorCode == null && System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        if (it.createdPlayers.isNotEmpty()) {
                            errorCode = it.player.playerError?.errorCodeName
                            ready = verify(it)
                        }
                    }
                    if (!ready) Thread.sleep(70)
                }
                assertNull("Real decoder/source error", errorCode)
                assertTrue("Expected decoded media/caption from $path ($caption), HTTP=$served", ready)
                scenario.onActivity { assertFalse(it.player.isReleased) }
            }
            assertTrue("Player must actually request the HTTP fixture", served.contains(path))
            if (path.endsWith("m3u8")) {
                assertTrue(served.contains("m467/child.m3u8"))
                assertTrue(served.any { it.endsWith(".ts") })
            }
            if (path.endsWith("mpd")) assertTrue(served.any { it.endsWith(".m4s") })
        } finally { server.shutdown() }
    }

    private fun decoded(activity: PlaybackTestActivity) =
        (activity.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 2 &&
            PlaybackTransport.options(activity.player, C.TRACK_TYPE_AUDIO).isNotEmpty()

    @Test fun mp4HttpRendersVideoAndAudio() = play("synthetic.mp4", verify = ::decoded)
    @Test fun matroskaHttpRendersVideoAndBothAudioTracks() = play("m467/synthetic.mkv") {
        decoded(it) && PlaybackTransport.options(it.player, C.TRACK_TYPE_AUDIO).size == 2
    }
    @Test fun hlsMasterChildAndSegmentsRender() = play("m467/master.m3u8", verify = ::decoded)
    @Test fun dashManifestAndFragmentedMp4Render() = play("m467/synthetic.mpd", verify = ::decoded)
    @Test fun srtIsDecodedAsSubrip() = caption("srt")
    @Test fun assTextIsDecoded() = caption("ass")
    @Test fun ssaTextIsDecoded() = caption("ssa")
    @Test fun vttIsDecoded() = caption("vtt")
    @Test fun ttmlTextIsDecoded() = caption("ttml")
    private fun caption(extension: String) = play("synthetic.mp4", extension) {
        decoded(it) && it.cueHistory.any { text -> text.contains("M467 caption decoded") }
    }
}
