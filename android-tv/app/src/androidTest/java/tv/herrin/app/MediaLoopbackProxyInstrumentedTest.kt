package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Proxy

/** Android socket/lifecycle regression; emulator success is not TCL device acceptance. */
@RunWith(AndroidJUnit4::class)
class MediaLoopbackProxyInstrumentedTest {
    @Test fun authorizedHlsAndRangeStayInsideClosableLoopbackSession() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\nsegment.ts\n"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/8").setBody("data"))
            val policy = MediaProxyPolicy.authorize("http://media.example:${server.port}/vod/master")
            val upstream = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build()
            val browser = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val proxy = MediaLoopbackProxy.startForTest(policy, upstream)
            proxy.use {
                val playlist = browser.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    response.body!!.string()
                }
                val segment = playlist.lineSequence().first { it.startsWith("http://127.0.0.1:") }
                browser.newCall(Request.Builder().url(segment).header("Range", "bytes=0-3").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("data", response.body!!.string())
                }
                assertEquals("/vod/master", server.takeRequest().path)
                assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
            }
            assertTrue(proxy.isClosed)
        }
    }

    @Test fun productionTransportRejectsLocalUpstreamRegardlessOfUserSuppliedSource() {
        MockWebServer().use { server ->
            val policy = MediaProxyPolicy.authorize("http://localhost:${server.port}/private")
            MediaLoopbackProxy.start(policy).use { proxy ->
                OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
                    .newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { response ->
                        assertEquals(502, response.code)
                        assertEquals(0, server.requestCount)
                    }
            }
        }
    }
}
