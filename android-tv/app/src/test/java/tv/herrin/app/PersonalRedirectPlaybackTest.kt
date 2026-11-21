package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PersonalRedirectPlaybackTest {
    @Test fun crossOriginLoopbackRedirectIsRejectedBeforeARequest() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            origin.start(); cdn.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/signed.mp4?ticket=fixture")))
            cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-0/10").setBody("x"))
            // Different ports are different origins; localhost spelling is
            // changed to an ordinary IP to exercise a real server redirect.
            val target = origin.url("/file.mp4").toString().replace("localhost", "127.0.0.1")
            // Cross-origin localhost targets are intentionally rejected.
            assertNull(HerrinPersonalMediaProbe().resolve(PlaybackMedia(target,
                headers = mapOf("Authorization" to "private", "Cookie" to "private"))))
            assertEquals(0, cdn.requestCount)
        } }
    }
    @Test fun actualCrossOriginRedirectStripsCredentialsBeforeOpeningCdn() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            origin.start(); cdn.start()
            val target = cdn.url("/signed.mp4").newBuilder().host("cdn.fixture.test").build()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location",target))
            cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type","video/mp4")
                .setHeader("Content-Range","bytes 0-0/10").setBody("x"))
            val client = okhttp3.OkHttpClient.Builder().followRedirects(false).dns {
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }.build()
            val result = HerrinPersonalMediaProbe(client).resolve(PlaybackMedia(
                origin.url("/movie").newBuilder().host("dav.fixture.test").build().toString(),
                headers=mapOf("Authorization" to "fixture", "Cookie" to "fixture")))
            assertEquals(target.toString(),result?.uri)
            assertEquals("fixture",origin.takeRequest().getHeader("Authorization"))
            val request = cdn.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("bytes=0-0",request.getHeader("Range"))
            assertTrue(result!!.headers.isEmpty())
        } }
    }
    @Test fun webDavAuthenticationFailureCannotBecomeSpiderAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(401))
            val result = verifySelectedPersonalMedia(PlaybackMedia(server.url("/movie.mp4").toString()))
            assertEquals("PERSONAL_AUTH_FAILED",(result as PlaybackResult.Unavailable).reason)
            assertFalse(PlayerStartupHints.forPersonalFailure(result.reason).contains("网盘授权"))
        }
    }
    @Test fun sameOriginDirectoryRedirectPreservesCredentialsAndResolvesPlayerUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/files/movie.mp4"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-0/10").setBody("x"))
            val result = HerrinPersonalMediaProbe().resolve(PlaybackMedia(server.url("/movie").toString(),
                headers = mapOf("Authorization" to "fixture")))
            assertEquals(server.url("/files/movie.mp4").toString(), result?.uri)
            assertEquals("fixture", server.takeRequest().getHeader("Authorization"))
            assertEquals("fixture", server.takeRequest().getHeader("Authorization"))
            assertFalse(PlayerStartupHints.forPersonalFailure("PERSONAL_AUTH_FAILED").contains("影视来源"))
        }
    }
}
