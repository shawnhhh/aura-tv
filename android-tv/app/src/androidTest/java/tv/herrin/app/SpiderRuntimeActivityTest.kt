package tv.herrin.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Android windows and Binder. No QR, provider login or first-frame claim. */
class SpiderRuntimeActivityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun runtimeActivityIsARealSecureWindowInTheWorkerAndRetiresWithItsOperation() = runBlocking {
        compose.setContent { Text("Activity capability contract") }
        val events = java.util.concurrent.CopyOnWriteArrayList<Lifecycle.Event>()
        val observer = LifecycleEventObserver { _, event -> events += event }
        compose.runOnIdle { compose.activity.lifecycle.addObserver(observer) }
        val bridge = TvBoxSpiderBridge(compose.activity)
        try {
            val result = withTimeout(15_000) { bridge.probeRemoteActivityForInstrumentation() }
            assertTrue(result is SpiderResult.Ready)
            val facts = (result as SpiderResult.Ready).value
            assertTrue(facts.getBoolean("host_ready"))
            assertTrue(facts.getBoolean("secure"))
            assertTrue(facts.getBoolean("window_attached"))
            assertNotEquals(android.os.Process.myPid(), facts.getInt("pid"))
            compose.waitUntil(5000) { compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            assertFalse("Translucent provider host must not stop the underlying search Activity", events.contains(Lifecycle.Event.ON_STOP))
        } finally {
            bridge.close()
            compose.runOnIdle { compose.activity.lifecycle.removeObserver(observer) }
        }
    }

    @Test fun applicationContextCannotInventAForegroundActivityForAProvider() = runBlocking {
        compose.setContent { Text("Activity capability denied contract") }
        val bridge = TvBoxSpiderBridge(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val result = withTimeout(15_000) { bridge.probeRemoteActivityForInstrumentation() }
            assertTrue(result is SpiderResult.Ready)
            val facts = (result as SpiderResult.Ready).value
            assertFalse(facts.getBoolean("host_ready"))
            assertFalse(facts.getBoolean("window_attached"))
        } finally { bridge.close() }
    }
}
