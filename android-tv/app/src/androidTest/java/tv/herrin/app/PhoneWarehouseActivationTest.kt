package tv.herrin.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Phone import becomes ACTIVE independently of the first CMS fetch; only one node is loaded. */
class PhoneWarehouseActivationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var importId: String
    private var beforeValues: Map<String, *> = emptyMap<String, Any?>()
    private val cmsRequests = AtomicInteger()
    private val received = AtomicLong()
    private val cmsStarted = CountDownLatch(1)
    private val allowCmsResponse = CountDownLatch(1)
    private val cmsResponded = AtomicBoolean(false)
    @get:Rule(order=0) val fixture = object : ExternalResource() {
        override fun before() {
            val prefs = context.getSharedPreferences("herrin",0)
            beforeValues = prefs.all
            prefs.edit().clear().commit()
            server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path?.substringBefore('?')) {
                        "/config.json" -> {
                            received.set(android.os.SystemClock.uptimeMillis())
                            MockResponse().setBody("""{"sites":[{"key":"catalog","name":"Controlled CMS","type":1,"api":"${server.url("/cms")}"}]}""")
                        }
                        "/cms" -> {
                            cmsRequests.incrementAndGet()
                            cmsStarted.countDown()
                            // Hold catalog delivery until after the phone import reports ACTIVE.
                            // This proves activation is not blocked by slow nodes.
                            check(allowCmsResponse.await(12, TimeUnit.SECONDS)) {
                                "Default CMS response was never released by the test"
                            }
                            cmsResponded.set(true)
                            MockResponse().setBody("""{"list":[]}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            importId = LanSourceImport(context).save(server.url("/config.json").toString()).id
        }
        override fun after() {
            allowCmsResponse.countDown()
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
    @Test fun phoneConfigurationActivatesBeforeFirstAutoSelectedCmsFinishes() {
        try {
            compose.waitUntil(10_000) {
                LanSourceImport(context).status(importId).optString("state") == "ACTIVE"
            }
            val elapsed = android.os.SystemClock.uptimeMillis() - received.get()
            assertTrue("Phone configuration was not downloaded", received.get() > 0L)
            assertEquals(server.url("/config.json").toString(), Preferences(context).tvBoxWarehouseUrl)
            assertTrue("The default CMS was not automatically selected",
                cmsStarted.await(10, TimeUnit.SECONDS))
            assertEquals("Exactly one first-node CMS request is permitted", 1, cmsRequests.get())
            assertFalse("Phone ACTIVE must precede completion of the CMS request", cmsResponded.get())
            android.util.Log.i("HerrinConfigFixture",
                "config_response_to_observed_active_ms=$elapsed cms_in_flight=1 controlled_http=true physical_TCL=false")
        } finally {
            allowCmsResponse.countDown()
        }
        compose.waitUntil(10_000) {
            Preferences(context).tvBoxLastWorkingSiteId.isNotBlank()
        }
        compose.waitForIdle()
        assertTrue("The selected CMS response must complete", cmsResponded.get())
        assertEquals("One auto-selected CMS; never scan all nodes", 1, cmsRequests.get())
        assertEquals("Phone import must stay ACTIVE after catalog returns", "ACTIVE",
            LanSourceImport(context).status(importId).optString("state"))
    }
}
