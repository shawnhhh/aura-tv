package tv.herrin.app

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpiderAuthorizationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean =
            super.bindService(Intent(this, SpiderAuthorizationFixtureService::class.java), conn, flags)
    }
    private fun show(): Pair<TvBoxSpiderBridge, Deferred<SpiderResult<List<Film>>>> {
        compose.setContent { MaterialTheme { SpiderAuthorizationHost() } }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"authorized":true,"token":"original-mock-token"}"""))
        server.start()
        val ctx = FixtureContext(inst.targetContext)
        val client = TvBoxSpiderBridge(ctx)
        val site = TvBoxSite("原创模拟授权", true, key = "auth-fixture", spiderApi = "csp_OriginalFixture", extension = server.url("/grant").toString().replace("localhost", "127.0.0.1"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = scope.async {
            try { client.browse(site, TvBoxConfig.Single(listOf(site), emptyList(), true)) }
            finally { server.shutdown() }
        }
        return client to result
    }
    @Test fun independentProcessMockAuthorizationUsesActualDpadAndReturnsOnlyStatus() = runBlocking {
        val (client, result) = show()
        try {
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("spider-authorization-dialog").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithTag("spider-authorization-qr").assertIsDisplayed()
            compose.onNodeWithTag("spider-authorization-action-0").assertIsFocused()
            val manager = inst.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            assertTrue(manager.runningAppProcesses.orEmpty().any { it.processName.endsWith(":spider_auth_test") && it.pid != android.os.Process.myPid() })
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val status = withTimeout(10_000) { result.await() } as SpiderResult.Failure
            assertEquals("mock-authorized", status.message)
            compose.waitUntil(5_000) { SpiderAuthorizationUi.requests.value == null }
        } finally { client.close() }
    }
    @Test fun backCancelsPendingPluginAndClearsPrivateQr() = runBlocking {
        val (client, result) = show()
        try {
            compose.waitUntil(15_000) { SpiderAuthorizationUi.requests.value != null }
            compose.waitForIdle()
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertEquals("mock-cancelled", (withTimeout(10_000) { result.await() } as SpiderResult.Failure).message)
            assertNull(SpiderAuthorizationUi.requests.value)
        } finally { client.close() }
    }
}
