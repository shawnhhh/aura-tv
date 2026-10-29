package tv.herrin.app

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class MediaLoopbackProxyTest {
    private val browser = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS).build()

    @Test fun rewritesRelativeHlsAndAttributeUrisAndStreamsAuthorizedRanges() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\nsegment.ts?key=secret\n"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/8").setHeader("Accept-Ranges", "bytes").setHeader("Set-Cookie", "must-not-escape=1").setBody("abcd"))
            session(server, headers = mapOf("Cookie" to "authorized=session")).use { proxy ->
                val local = get(proxy.playbackUrl).use { response ->
                    assertEquals(200, response.code)
                    response.body!!.string()
                }
                assertFalse(local.contains("secret"))
                assertFalse(local.contains("media.example"))
                val mapUri = Regex("URI=\"([^\"]+)\"").find(local)!!.groupValues[1]
                assertTrue(mapUri.startsWith("http://127.0.0.1:"))
                val segment = local.lineSequence().first { it.startsWith("http://") }
                get(segment, mapOf("Range" to "bytes=0-3", "Authorization" to "injected", "Cookie" to "injected", "X-Forwarded-For" to "injected")).use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 0-3/8", response.header("Content-Range"))
                    assertNull(response.header("Set-Cookie"))
                    assertEquals("abcd", response.body!!.string())
                }
                val root = server.takeRequest(2, TimeUnit.SECONDS)!!
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/vod/master", root.path)
                assertEquals("/vod/segment.ts?key=secret", request.path)
                assertEquals("bytes=0-3", request.getHeader("Range"))
                assertEquals("authorized=session", request.getHeader("Cookie"))
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Forwarded-For"))
            }
        }
    }

    @Test fun extensionlessHlsAndNestedPlaylistsUseFinalSameOriginRedirectBase() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/nested/manifest"))
            server.enqueue(MockResponse().setBody("#EXTM3U\nchild/next\n"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n../s.ts\n"))
            session(server).use { proxy ->
                val first = get(proxy.playbackUrl).use { it.body!!.string() }.lineSequence().first { it.startsWith("http://") }
                val second = get(first).use { it.body!!.string() }
                assertTrue(second.contains("http://127.0.0.1:"))
                assertEquals("/vod/master", server.takeRequest().path)
                assertEquals("/nested/manifest", server.takeRequest().path)
                assertEquals("/nested/child/next", server.takeRequest().path)
            }
        }
    }

    @Test fun tokenUnknownResourceMethodsHostAndRangesCannotTurnSessionIntoOpenProxy() {
        MockWebServer().use { server ->
            session(server).use { proxy ->
                val root = java.net.URI(proxy.playbackUrl)
                get(proxy.playbackUrl.replace(root.path.split('/')[1], "wrong-token")).use { assertEquals(403, it.code) }
                get(proxy.playbackUrl + "?url=http://169.254.169.254/").use { assertEquals(404, it.code) }
                get(proxy.playbackUrl.substringBeforeLast('/') + "/unknown").use { assertEquals(404, it.code) }
                get(proxy.playbackUrl, mapOf("Range" to "bytes=0-1,4-5")).use { assertEquals(416, it.code) }
                get(proxy.playbackUrl, mapOf("Origin" to "https://evil.example")).use { assertEquals(403, it.code) }
                get(proxy.playbackUrl, mapOf("Host" to "evil.example")).use { assertEquals(403, it.code) }
                browser.newCall(Request.Builder().url(proxy.playbackUrl).post(okhttp3.RequestBody.create(null, "")).build()).execute().use { assertEquals(405, it.code) }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun crossOriginRedirectsAndManifestUrlsAndEncryptionFailClosed() {
        for (body in listOf("#EXTM3U\nhttps://evil.example/seg.ts\n", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\ns.ts\n", "#EXTM3U\n#EXT-X-MEDIA:URI=\"http://127.0.0.1/secret\"\n", "#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"https://evil.example\"\n", "#EXTM3U\n{\$uri}\n")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                session(server).use { proxy -> get(proxy.playbackUrl).use { assertEquals(body, 502, it.code) } }
                assertEquals(1, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/private"))
            session(server).use { proxy -> get(proxy.playbackUrl).use { assertEquals(502, it.code) } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsAreBoundedAndUpstreamFailuresAreSanitized() {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
            session(server).use { proxy -> get(proxy.playbackUrl).use { assertEquals(502, it.code) } }
            assertEquals(4, server.requestCount)
        }
        for (code in listOf(401, 403, 404, 429, 500, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("sensitive URL and cookie").setHeader("Set-Cookie", "sensitive").setHeader("Retry-After", "5"))
                session(server).use { proxy -> get(proxy.playbackUrl).use { response ->
                    assertEquals(code, response.code)
                    assertEquals("", response.body!!.string())
                    assertNull(response.header("Set-Cookie"))
                    assertEquals("5", response.header("Retry-After"))
                } }
            }
        }
    }

    @Test fun oversizedManifestIsRejectedAndHeadDoesNotReadBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/x-mpegurl").setBody("#EXTM3U\n" + "x".repeat(1024 * 1024)))
            server.enqueue(MockResponse().setHeader("Content-Length", "9999999").setHeader("Content-Type", "video/mp4"))
            session(server).use { proxy ->
                get(proxy.playbackUrl).use { assertEquals(502, it.code) }
                browser.newCall(Request.Builder().url(proxy.playbackUrl).head().build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("9999999", it.header("Content-Length"))
                }
            }
        }
    }

    @Test fun closeIsIdempotentReleasesListenerAndSessionsHaveDistinctTokens() {
        MockWebServer().use { server ->
            val first = session(server)
            val second = session(server)
            assertNotEquals(java.net.URI(first.playbackUrl).path, java.net.URI(second.playbackUrl).path)
            assertFalse(first.toString().contains(java.net.URI(first.playbackUrl).path))
            val port = java.net.URI(first.playbackUrl).port
            val url = first.playbackUrl
            first.close(); first.close(); second.close()
            assertTrue(first.isClosed)
            try { Socket("127.0.0.1", port).use { fail("Listener still open") } } catch (_: java.net.ConnectException) { }
            try { get(url).close(); fail("Session should be closed") } catch (_: java.io.IOException) { }
        }
    }

    @Test fun productionDnsActuallyBlocksLoopbackEvenIfExplicitlyAuthorized() {
        MockWebServer().use { server ->
            MediaLoopbackProxy.start(MediaProxyPolicy.authorize("http://localhost:${server.port}/secret")).use { proxy ->
                get(proxy.playbackUrl).use { assertEquals(502, it.code) }
                assertEquals(0, server.requestCount)
            }
        }
    }

    private fun session(server: MockWebServer, headers: Map<String, String> = emptyMap()): MediaLoopbackProxy {
        val url = "http://media.example:${server.port}/vod/master"
        val transport = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
            .callTimeout(5, TimeUnit.SECONDS).build()
        return MediaLoopbackProxy.startForTest(MediaProxyPolicy.authorize(url, headers), transport)
    }
    private fun get(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val request = Request.Builder().url(url)
        headers.forEach { (key, value) -> request.header(key, value) }
        return browser.newCall(request.build()).execute()
    }
}
