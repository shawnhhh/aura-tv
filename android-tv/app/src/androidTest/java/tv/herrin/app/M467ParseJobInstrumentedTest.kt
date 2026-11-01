package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android WebView + direct upstream ParseJob, using only local authorized fixtures. */
@RunWith(AndroidJUnit4::class)
class M467ParseJobInstrumentedTest {
    @Test fun browserRecognizesExtensionlessHttpMediaAndRejectsFakeMp4Html() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/jx?") == true -> MockResponse().setHeader("Content-Type","text/html")
                        .setBody("""<html><body><script>fetch('/fake.mp4').then(()=>fetch('/opaque'));</script></body></html>""")
                    request.path == "/fake.mp4" -> MockResponse().setHeader("Content-Type","text/html").setBody("<html>not media</html>")
                    request.path == "/opaque" -> MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-ENDLIST\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val parser = TvBoxPlaybackParser("web",0,server.url("/jx?url=").toString())
            val resolved = runBlocking { FongMiParsePlayback.resolve(context,
                """{"parse":1,"playUrl":"parse:web","url":"https://watch.test/opaque"}""",
                "vip",emptyList(),true,playbackParsers=listOf(parser)) }
            assertTrue("Actual WebView and MIME probe must resolve: $resolved",resolved is PlaybackResult.Ready)
            assertEquals(server.url("/opaque").toString(),(resolved as PlaybackResult.Ready).media.uri)
        }
    }
    @Test fun aggregateJsonFailureFallsThroughToRealWebView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/json?") == true -> MockResponse().setBody("{\"url\":\"\"}")
                    request.path?.startsWith("/web?") == true -> MockResponse().setHeader("Content-Type","text/html")
                        .setBody("""<html><body><script>fetch('/media');</script></body></html>""")
                    request.path == "/media" -> MockResponse().setHeader("Content-Type","video/mp4").setBody("media mime fixture")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val parsers = listOf(TvBoxPlaybackParser("all",4,""),
                TvBoxPlaybackParser("json",1,server.url("/json?url=").toString()),
                TvBoxPlaybackParser("web",0,server.url("/web?url=").toString()))
            val resolved = runBlocking { FongMiParsePlayback.resolve(context,
                """{"parse":1,"playUrl":"parse:all","url":"https://watch.test/opaque"}""",
                "vip",emptyList(),true,playbackParsers=parsers) }
            assertTrue("Failed JSON must not terminate WebView competitor: $resolved",resolved is PlaybackResult.Ready)
            assertEquals(server.url("/media").toString(),(resolved as PlaybackResult.Ready).media.uri)
        }
    }
}
