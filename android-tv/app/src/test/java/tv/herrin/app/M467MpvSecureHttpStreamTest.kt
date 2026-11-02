package tv.herrin.app

import java.nio.ByteBuffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp loopback tests; no native MPV symbols are loaded. */
class M467MpvSecureHttpStreamTest {
    @Test fun readsRealHttpBytesAndResumesFromExactRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij"))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-9/10").setBody("ghij"))
            server.start()
            MpvSecureHttpStream(server.url("/film.mp4").toString(),
                mapOf("Referer" to "https://licensed.invalid/")).useStream { stream ->
                val first = ByteBuffer.allocateDirect(4)
                assertEquals(4, stream.read(first))
                first.flip()
                assertEquals("abcd", Charsets.UTF_8.decode(first).toString())
                assertEquals(10L, stream.size())
                assertEquals(6L, stream.seek(6))
                val second = ByteBuffer.allocateDirect(8)
                assertEquals(4, stream.read(second))
                second.flip()
                assertEquals("ghij", Charsets.UTF_8.decode(second).toString())
                assertEquals(0, stream.read(ByteBuffer.allocateDirect(8)))
                val initial = server.takeRequest()
                val resumed = server.takeRequest()
                assertNull(initial.getHeader("Range"))
                assertEquals("bytes=6-", resumed.getHeader("Range"))
                assertEquals("https://licensed.invalid/", resumed.getHeader("Referer"))
            }
        }
    }

    @Test fun refusedRangeReplyNeverReturnsWrongVideoBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij"))
            server.enqueue(MockResponse().setBody("abcdefghij"))
            server.start()
            MpvSecureHttpStream(server.url("/film").toString(), emptyMap()).useStream { stream ->
                assertEquals(1, stream.read(ByteBuffer.allocateDirect(1)))
                assertEquals(5L, stream.seek(5))
                assertEquals(-1, stream.read(ByteBuffer.allocateDirect(8)))
                assertEquals("bytes=5-", server.takeRequestAfterFirst())
            }
        }
    }

    @Test fun redirectDoesNotForwardAuthorizationToAnotherOrigin() {
        MockWebServer().use { origin ->
            MockWebServer().use { differentOrigin ->
                differentOrigin.start()
                origin.enqueue(MockResponse().setResponseCode(302)
                    .setHeader("Location", differentOrigin.url("/stolen").toString()))
                origin.start()
                MpvSecureHttpStream(origin.url("/licensed.mp4").toString(),
                    mapOf("Authorization" to "Bearer fixture-only")).useStream { stream ->
                    assertEquals(-1, stream.read(ByteBuffer.allocateDirect(32)))
                }
                assertEquals("Bearer fixture-only", origin.takeRequest().getHeader("Authorization"))
                assertEquals(0, differentOrigin.requestCount)
            }
        }
    }

    @Test fun rejectsEmbeddedCredentialsNewlineHeadersAndInvalidSeek() {
        assertThrows(IllegalArgumentException::class.java) {
            MpvSecureHttpStream("https://username:password@licensed.invalid/film.mp4", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MpvSecureHttpStream("https://licensed.invalid/film.mp4", mapOf("X-H" to "one\r\nInjected:two"))
        }
        val stream = MpvSecureHttpStream("https://licensed.invalid/film.mp4", emptyMap())
        assertEquals(-1L, stream.seek(-1L))
        stream.cancel()
        assertEquals(-1, stream.read(ByteBuffer.allocateDirect(10)))
        assertEquals(-1L, stream.seek(1))
        stream.close()
    }

    private fun MpvSecureHttpStream.useStream(block: (MpvSecureHttpStream) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun MockWebServer.takeRequestAfterFirst(): String? {
        takeRequest()
        return takeRequest().getHeader("Range")
    }
}
