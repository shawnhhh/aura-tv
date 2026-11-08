package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual original media bytes over loopback; not a third-party source claim. */
class P0SelectedPersonalPlaybackTest {
    @Test fun selectedPersonalFileMustPassHeadersAndActualMediaBytesBeforeReady()=runBlocking {
        val video=File("src/androidTest/assets/synthetic.mp4").readBytes()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type","video/mp4")
                .setHeader("Content-Range","bytes 0-0/${video.size}").setBody(Buffer().write(video,0,1)))
            server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video)))
            val media=PlaybackMedia(server.url("/owned.mp4").toString(),headers=mapOf("Authorization" to "fixture-only"),allowHttpRedirects=true)
            assertEquals(media,(verifySelectedPersonalMedia(media) as PlaybackResult.Ready).media)
            assertEquals(2,server.requestCount)
            assertEquals("bytes=0-0",server.takeRequest().getHeader("Range"))
            assertEquals("bytes=0-1023",server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun successfulHeadersCannotPromoteAnHtmlLoginBodyToPersonalPlayback()=runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setBody("header-probe-only"))
            server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setBody("<html>expired login</html>"))
            val media=PlaybackMedia(server.url("/owned.mp4?token=private-fixture").toString(),allowHttpRedirects=true)
            val result=verifySelectedPersonalMedia(media)
            assertTrue(result is PlaybackResult.Unavailable)
            assertFalse((result as PlaybackResult.Unavailable).reason.contains("private-fixture"))
            assertEquals(2,server.requestCount)
        }
    }
}
