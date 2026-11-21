package tv.herrin.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual pending-phone → Activity → HTTP config → saved root. No plugin/auth/media fixture. */
class PhoneWarehouseActivationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var importId: String
    private var beforeValues: Map<String, *> = emptyMap<String, Any?>()
    private val cmsRequests = AtomicInteger()
    private val received = AtomicLong()
    @get:Rule(order=0) val fixture = object : ExternalResource() {
        override fun before() {
            val prefs = context.getSharedPreferences("herrin",0)
            beforeValues = prefs.all
            prefs.edit().clear().commit()
            server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.path == "/config.json") {
                        received.set(android.os.SystemClock.uptimeMillis())
                        return MockResponse().setBody("""{"sites":[{"key":"catalog","name":"Controlled CMS","type":1,"api":"${server.url("/cms")}"}]}""")
                    }
                    cmsRequests.incrementAndGet()
                    return MockResponse().setBody("""{"list":[]}""")
                }
            }
            server.start()
            importId = LanSourceImport(context).save(server.url("/config.json").toString()).id
        }
        override fun after() {
            server.shutdown()
            val edit = context.getSharedPreferences("herrin",0).edit().clear()
            beforeValues.forEach { (k,v) -> when(v) {
                is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v)
                is Int -> edit.putInt(k,v); is Long -> edit.putLong(k,v); is Float -> edit.putFloat(k,v)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>) }
            } }
            edit.commit()
        }
    }
    @get:Rule(order=1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun phoneConfigurationBecomesActiveWithoutRequestingTheCmsHome() {
        compose.waitUntil(10_000) { LanSourceImport(context).status(importId).optString("state") == "ACTIVE" }
        val elapsed = android.os.SystemClock.uptimeMillis() - received.get()
        assertTrue(received.get() > 0)
        assertEquals(server.url("/config.json").toString(),Preferences(context).tvBoxWarehouseUrl)
        compose.waitForIdle()
        assertEquals("Metadata activation must not initialize a node",0,cmsRequests.get())
        android.util.Log.i("HerrinConfigFixture","config_response_to_observed_active_ms=$elapsed cms_requests=0 controlled_http=true physical_TCL=false")
    }
}
