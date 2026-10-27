package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

/** HTTP tests use only a local fixture server; no third-party TVBox service is contacted. */
class TvBoxWarehouseIntegrationTest {
    @Test fun fallsBackToPreviouslyValidatedJsonWhenSourceBecomesImage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val address = server.url("/tv").toString()
            val repo = TvBoxConfigRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"sites":[{"name":"备用演示"}],"lives":[]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/x-ms-bmp")
                .setBody("not-a-json-image"))
            val first = repo.load(address, allowHttp = true) as TvBoxConfig.Single
            assertFalse(first.fromCache)
            val second = repo.load(address, allowHttp = true) as TvBoxConfig.Single
            assertTrue(second.fromCache)
            assertEquals("备用演示", second.sites.single().name)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun manualJsonImportUnlocksOfflineFallbackButNeverBypassesHttpConsent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val address = server.url("/expired-source").toString()
            val repo = TvBoxConfigRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            val json = """{"sites":[],"lives":[{"name":"家庭直播","channels":[{"name":"测试台","urls":["https://example.org/test.m3u8"]}]}]}"""
            assertTrue(repo.importJson(json, address, allowHttp = false) is TvBoxConfig.Error)
            assertEquals(0, server.requestCount)
            assertTrue(repo.importJson(json, address, allowHttp = true) is TvBoxConfig.Single)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/x-ms-bmp")
                .setBody("not-a-json-image"))
            assertTrue(repo.load(address, allowHttp = false) is TvBoxConfig.Error)
            val cached = repo.load(address, allowHttp = true) as TvBoxConfig.Single
            assertTrue(cached.fromCache)
            assertEquals("测试台", cached.lives.single().channels.single().name)
        }
    }
    @Test fun explicitlyAuthorizedHttpNavigatesMultiwarehouseToSingle() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val warehouse = server.url("/single.json").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"urls":[{"name":"我自己的仓库","url":"$warehouse"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
                .setBody("""{"sites":[{"name":"测试站","api":"csp_Example"}],"lives":[]}"""))
            val rootUrl = server.url("/warehouse.json").toString()
            val repo = TvBoxConfigRepository()
            assertTrue(repo.load(rootUrl) is TvBoxConfig.Error)
            assertEquals(0, server.requestCount)
            val root = repo.load(rootUrl, allowHttp = true) as TvBoxConfig.Warehouses
            assertEquals(1, root.entries.size)
            assertEquals("我自己的仓库", root.entries[0].name)
            val selected = repo.load(root.entries[0].url, allowHttp = true) as TvBoxConfig.Single
            assertEquals("测试站", selected.sites.single().name)
            assertTrue(selected.hasExternalEngine)
            assertEquals("/warehouse.json", server.takeRequest().path)
            assertEquals("/single.json", server.takeRequest().path)
        }
    }

    @Test fun refusesRedirectsHtmlAndOversizedPayloads() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example.org"))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/x-ms-bmp").setBody("JPEG payload"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{" + " ".repeat(1024 * 1024) + "}"))
            val repo = TvBoxConfigRepository()
            val url = server.url("/config").toString()
            val redirect = repo.load(url, allowHttp = true) as TvBoxConfig.Error
            assertTrue(redirect.reason.contains("跳转"))
            val image = repo.load(url, allowHttp = true) as TvBoxConfig.Error
            assertTrue(image.reason.contains("图片"))
            val overLimit = repo.load(url, allowHttp = true) as TvBoxConfig.Error
            assertTrue(overLimit.reason.contains("1 MB"))
            assertEquals(3, server.requestCount)
        }
    }
}
