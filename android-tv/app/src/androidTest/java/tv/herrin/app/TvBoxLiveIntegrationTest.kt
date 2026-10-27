package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TvBoxLiveIntegrationTest {
    @Test fun httpPlaylistNeedsConsentAndParsesRealChannelEntries() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = TvBoxLiveRepository()
            val address = server.url("/live.txt").toString()
            assertTrue(repo.load(address, allowHttp = false) is TvBoxLiveResult.Error)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("央视,#genre#\nCCTV1,https://example.org/cctv1.m3u8\n"))
            val loaded = repo.load(address, allowHttp = true) as TvBoxLiveResult.Ready
            assertEquals(1, loaded.channels.size)
            assertEquals("央视", loaded.channels[0].group)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun importedChannelsPersistAcrossNewStoreAndRequireExplicitHttpConsent() {
        val store = TvBoxLiveStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val expected = listOf(LiveChannel("高清台", "https://example.org/hd.m3u8", "公共频道"),
            LiveChannel("专网台", "http://192.168.31.2/live", "本地 IPTV"))
        assertTrue(store.save(expected, allowHttp = false))
        assertEquals(1, TvBoxLiveStore(InstrumentationRegistry.getInstrumentation().targetContext).load().size)
        assertTrue(store.save(expected, allowHttp = true))
        assertEquals(2, TvBoxLiveStore(InstrumentationRegistry.getInstrumentation().targetContext).load().size)
    }

    @Test fun rejectsImageResponseEvenWithHttpPermission() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/x-ms-bmp").setBody("jpeg"))
            val result = TvBoxLiveRepository().load(server.url("/tv").toString(), allowHttp = true)
            assertTrue(result is TvBoxLiveResult.Error)
            assertTrue((result as TvBoxLiveResult.Error).reason.contains("图片"))
        }
    }
}
