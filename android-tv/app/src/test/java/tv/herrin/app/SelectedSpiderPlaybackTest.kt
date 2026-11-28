package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class SelectedSpiderPlaybackTest {
    @Test fun slowCallbackIsOpenedOnceByPlayerTransportAfterAuthorization() = runBlocking {
        val calls = AtomicInteger()
        val events = mutableListOf<String>()
        NativeFongMiProxy("approved", { _ ->
            calls.incrementAndGet()
            Thread.sleep(1700) // Longer than the removed independent 1500ms media probe.
            arrayOf(200, "video/mp4", ByteArrayInputStream(ByteArray(32) { 7 }))
        }, null).use { proxy ->
            val media = PlaybackMedia(proxy.siteUrl())
            assertTrue(SelectedSpiderPlayback.admit(media, "approved", { error("No second media GET") }, events::add))
            assertEquals(0, calls.get())
            assertTrue(events.contains("SELECTED_PROXY_MEDIA_DEFERRED_TO_PLAYER"))
            val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
            client.newCall(Request.Builder().url(media.uri).build()).execute().use { reply ->
                assertEquals(200, reply.code)
                assertEquals(32, reply.body!!.bytes().size)
            }
            assertEquals(1, calls.get())
        }
    }

    @Test fun foreignAndRetiredSessionsNeverReachTheMediaCallback() = runBlocking {
        val calls = AtomicInteger()
        val proxy = NativeFongMiProxy("approved", { _ -> calls.incrementAndGet(); null }, null)
        val media = PlaybackMedia(proxy.siteUrl())
        assertFalse(SelectedSpiderPlayback.admit(media, "foreign", { error("No probe") }))
        assertFalse(SelectedSpiderPlayback.admit(media, null, { error("No probe") }))
        proxy.close()
        assertFalse(SelectedSpiderPlayback.admit(media, "approved", { error("No probe") }))
        assertEquals(0, calls.get())
    }

    @Test fun remoteCandidateStillRequiresMediaValidation() = runBlocking {
        var probes = 0
        assertFalse(SelectedSpiderPlayback.admit(PlaybackMedia("https://example.org/movie.m3u8"), "approved", { probes++; false }))
        assertEquals(1, probes)
    }
}
