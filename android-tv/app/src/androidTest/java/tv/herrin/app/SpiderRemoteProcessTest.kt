package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.herrin.app.nativeplayback.NativeFongMiRoute

/** Uses a nonexistent JAR, so no downloaded plugin code is executed in CI. */
class SpiderRemoteProcessTest {
    @Test fun androidMainThreadProbeFailureIsNotADeadProxy() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bridge = TvBoxSpiderBridge(inst.targetContext)
        try {
            val url = (bridge.probeRemoteProxyForInstrumentation() as SpiderResult.Ready).value
            inst.runOnMainSync {
                val previous = android.os.StrictMode.getThreadPolicy()
                try {
                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                        .detectNetwork().penaltyDeathOnNetwork().build())
                    val health = NativeFongMiRoute.probe(url, "ci-proxy")
                    assertEquals(NativeFongMiRoute.Health.IO_MAIN_THREAD, health)
                    assertTrue(!FongMiPlaybackRecovery.canRebuildProxy(health.name))
                } finally { android.os.StrictMode.setThreadPolicy(previous) }
            }
            assertEquals("The same listener is healthy off Main", NativeFongMiRoute.Health.READY,
                NativeFongMiRoute.probe(url, "ci-proxy"))
        } finally { bridge.close() }
    }

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
    @Test fun isolatedSpiderServicePreservesNativeFongMiDoOperationsAcrossBinder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = TvBoxSpiderBridge(context)
        try {
            val initial = bridge.probeRemoteProxyForInstrumentation()
            assertTrue("Debug-only native Spider route must be returned",
                initial is SpiderResult.Ready)
            val url = (initial as SpiderResult.Ready).value
            // The legacy loopback implementation rejects all but do=jar.
            // An actual native FongMi route must forward an authorized do=part.
            val changed = url.replace("&do=jar", "&do=part")
            assertTrue("Fixture must exercise the part operation", changed != url)
            val target = java.net.URL(changed).openConnection() as java.net.HttpURLConnection
            try {
                target.connectTimeout = 2_000
                target.readTimeout = 3_000
                assertTrue("do=part should be dispatched by the real :spider process",
                    target.responseCode == 200)
                assertTrue("Real bytes must cross :spider -> main process",
                    target.inputStream.use { it.readBytes().contentEquals(byteArrayOf(8, 9, 10)) })
            } finally {
                target.disconnect()
            }
        } finally {
            bridge.close()
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
                NativeFongMiRoute.probe(uri, "ci-proxy") == NativeFongMiRoute.Health.READY)
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
