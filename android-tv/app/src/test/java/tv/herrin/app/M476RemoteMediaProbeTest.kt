package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class M476RemoteMediaProbeTest {
    private fun probe(authorized: Boolean = true) = HerrinRemoteMediaProbe(
        authorizeLocal = { authorized })

    @Test fun actuallyReadsVideoBytesAndPreservesOnlyCurrentSourceAuth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-15/8192")
                .setBody("0123456789ABCDEF"))
            server.start()
            val url = server.url("/film.mp4").toString()
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA,
                probe().probe(PlaybackMedia(url,
                    mapOf("Authorization" to "Bearer authorized-only"))))
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("bytes=0-1023", sent.getHeader("Range"))
            assertEquals("Bearer authorized-only", sent.getHeader("Authorization"))
        }
    }

    @Test fun rejectsEmptyHtmlLoginAndForgedPartialContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "video/mp4").setBody(""))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 3-4/900").setBody("xx"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("<!DOCTYPE html>login"))
            server.start()
            val media = PlaybackMedia(server.url("/media.mp4").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.LOGIN_OR_TEXT, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.EMPTY_BODY, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_RANGE, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.LOGIN_OR_TEXT, probe().probe(media))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun hlsMustContainActualManifestNotAnHtmlErrorPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nchunk.ts\n"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("Your payment is required"))
            server.start()
            val media = PlaybackMedia(server.url("/master.m3u8").toString())
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, probe().probe(media))
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_MANIFEST, probe().probe(media))
        }
    }

    @Test fun localProxyNeedsCurrentSessionAndRedirectNeverTargetsLoopback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("video"))
            server.start()
            val raw = server.url("/not-approved").toString()
            assertEquals(HerrinRemoteMediaProbe.Result.UNAUTHORIZED_LOCAL,
                probe(authorized = false).probe(PlaybackMedia(raw)))
            assertEquals(0, server.requestCount)
        }
        MockWebServer().use { source ->
            source.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:9987/private"))
            source.start()
            assertEquals(HerrinRemoteMediaProbe.Result.INVALID_URL,
                probe().probe(PlaybackMedia(source.url("/redirect").toString(),
                    mapOf("Authorization" to "private"))))
            assertEquals(1, source.requestCount)
        }
    }
}
