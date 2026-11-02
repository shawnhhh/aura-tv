package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class M471PersonalMediaProbeTest {
    @Test fun rangeProbeChecksOnlyOneByteWithAuthorizedHeader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-0/16777216")
                .setHeader("Content-Length", "1").setBody("x"))
            server.start()
            val media = PlaybackMedia(server.url("/library/movie.mp4").toString(),
                mapOf("Authorization" to "Basic owned-test-token"), allowHttpRedirects = false)
            assertTrue(HerrinPersonalMediaProbe().canRead(media))
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("bytes=0-0", request.getHeader("Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Basic owned-test-token", request.getHeader("Authorization"))
        }
    }

    @Test fun deniedFileFallsBackWithoutPassingAuthorizationToOtherOrigin() = runBlocking {
        MockWebServer().use { nas ->
            MockWebServer().use { cloud ->
                nas.enqueue(MockResponse().setResponseCode(403)
                    .setHeader("Content-Type", "text/html").setBody("login required"))
                cloud.enqueue(MockResponse().setResponseCode(206)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Range", "bytes 0-0/12345").setBody("z"))
                nas.start()
                cloud.start()
                val probe = HerrinPersonalMediaProbe()
                val first = PlaybackMedia(nas.url("/movie.mkv").toString(),
                    mapOf("Authorization" to "Bearer nas-only"))
                val second = PlaybackMedia(cloud.url("/movie.mkv").toString(),
                    mapOf("Authorization" to "Bearer cloud-only"))
                assertFalse(probe.canRead(first))
                assertTrue(probe.canRead(second))
                assertEquals("Bearer nas-only", nas.takeRequest().getHeader("Authorization"))
                assertEquals("Bearer cloud-only", cloud.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test fun redirectToLoginNeverFollowsOrSendsCredentialsToLocation() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { attacker ->
                attacker.start()
                origin.enqueue(MockResponse().setResponseCode(302)
                    .setHeader("Location", attacker.url("/collect").toString()))
                origin.start()
                assertFalse(HerrinPersonalMediaProbe().canRead(PlaybackMedia(
                    origin.url("/file.mp4").toString(),
                    mapOf("Authorization" to "Basic private"))))
                assertEquals(1, origin.requestCount)
                assertEquals(0, attacker.requestCount)
            }
        }
    }

    @Test fun fakeSuccessHtmlOrBrokenPartialRangeCannotMasqueradeAsVideo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("sign in"))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 1-1/5000").setBody("x"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream").setBody("test"))
            server.start()
            val media = PlaybackMedia(server.url("/movie.mp4").toString())
            val probe = HerrinPersonalMediaProbe()
            assertFalse(probe.canRead(media))
            assertFalse(probe.canRead(media))
            assertTrue(probe.canRead(media)) // server ignored Range but sent opaque media
            assertEquals(3, server.requestCount)
        }
    }
}
