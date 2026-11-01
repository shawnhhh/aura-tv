package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a nonexistent JAR, so no downloaded plugin code is executed in CI. */
class SpiderRemoteProcessTest {
    @Test fun invalidSpiderFailureReturnsFromRemoteServiceWithoutKillingUi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = TvBoxSpiderBridge(context)
        val site = TvBoxSite("合成测试站点", needsAdapter = true,
            key = "synthetic", spiderApi = "csp_Synthetic")
        val config = TvBoxConfig.Single(listOf(site), emptyList(), true)
        try {
            val first = client.browse(site, config)
            assertTrue("Invalid configuration must fail cleanly via IPC", first is SpiderResult.Failure)
            client.close()
            // A user may reapply a source after a failed request. The bridge must rebind.
            val second = client.browse(site, config)
            assertTrue("Re-applying must not permanently close IPC", second is SpiderResult.Failure)
        } finally {
            client.close()
        }
    }
    @Test fun actualIsolatedSpiderServiceProxySocketIsReachableFromMainProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = TvBoxSpiderBridge(context)
        try {
            val probe = bridge.probeRemoteProxyForInstrumentation()
            assertTrue("Remote Spider self-test must return a live URL: $probe",
                probe is SpiderResult.Ready)
            val uri = (probe as SpiderResult.Ready).value
            assertTrue("The main process must reach :spider health endpoint",
                SpiderProxyPlayback.ping(uri))
            val connection = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 3_000
                assertTrue("Proxy must reply to main-process socket", connection.responseCode == 200)
                assertTrue(connection.inputStream.use {
                    it.readBytes().contentEquals(byteArrayOf(8, 9, 10))
                })
            } finally { connection.disconnect() }
        } finally { bridge.close() }
    }
}
