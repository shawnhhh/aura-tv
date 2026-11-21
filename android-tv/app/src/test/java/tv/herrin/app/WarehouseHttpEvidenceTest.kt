package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Original protocol fixture only, never a real warehouse or playback verdict. */
class WarehouseHttpEvidenceTest {
    private val original = """{"sites":[{"key":"original","name":"Original metadata","api":"csp_Fixture"}]}"""

    @Test fun deniedTransportCannotProduceAnHttpResponseObservation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val observed = mutableListOf<Int>()
            val repo = TvBoxConfigRepository(onImportHttpStatus = observed::add)
            assertTrue(repo.load(server.url("/original").toString(), allowHttp = false) is TvBoxConfig.Error)
            assertTrue(observed.isEmpty())
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody(original))
            assertTrue(repo.loadUserWarehouse(server.url("/original").toString()) is TvBoxConfig.Single)
            assertEquals(listOf(200), observed)
        }
    }

    @Test fun diagnosticFailureCannotReplaceSuccessfulConfiguration() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(original))
            val repo = TvBoxConfigRepository(onImportHttpStatus = { throw IllegalStateException("Original observer failure") })
            assertTrue(repo.loadUserWarehouse(server.url("/original").toString()) is TvBoxConfig.Single)
        }
    }
}
