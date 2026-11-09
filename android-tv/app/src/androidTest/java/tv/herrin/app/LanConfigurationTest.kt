package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

/** HTTPS startup/transport check; no trust-all client and no live credentials. */
class LanConfigurationTest {
    @Test fun confirmedTrustedSessionCanSwitchModulesAndEnd() {
        val server = LanConfigServer(InstrumentationRegistry.getInstrumentation().targetContext, "127.0.0.1") { }
        try {
            server.start(5000, true)
            val trust = okhttp3.tls.HandshakeCertificates.Builder()
                .addTrustedCertificate(server.certificate.certificate).build()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            fun post(path: String, json: String, session: String = ""): Pair<Int, String> {
                val request = Request.Builder().url(server.origin + path)
                    .header("Origin", server.origin).header("X-Herrin-Session", session)
                    .post(json.toRequestBody("application/json".toMediaType())).build()
                return client.newCall(request).execute().use { it.code to it.body!!.string() }
            }
            assertEquals(403, post("/status", "{}").first)
            val body = org.json.JSONObject().put("pairing", server.pairing.pairing).toString()
            assertTrue(org.json.JSONObject(post("/pair", body).second).getBoolean("pending"))
            server.pairing.confirm()
            val session = org.json.JSONObject(post("/pair", body).second).getString("session")
            assertTrue(session.isNotBlank())
            assertEquals(403, post("/pair", body).first)
            val state = post("/status", "{}", session)
            assertEquals(200, state.first)
            assertEquals("WebDAV", org.json.JSONObject(state.second).getString("nasProtocol"))
            assertFalse(org.json.JSONObject(state.second).has("token"))
            val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
            val previous = prefs.tvBoxWarehouseUrl
            val trustedRoot=prefs.tvBoxTrustedSpiderRootUrl
            val trustedJar=prefs.tvBoxTrustedSpiderJarSpec
            try {
                assertEquals(200, post("/sources", org.json.JSONObject().put("url", "http://饭太硬.net/tv").toString(), session).first)
                assertEquals(TvBoxUrl.normalize("http://饭太硬.net/tv", true), prefs.tvBoxWarehouseUrl)
                assertEquals(trustedRoot,prefs.tvBoxTrustedSpiderRootUrl)
                assertEquals(trustedJar,prefs.tvBoxTrustedSpiderJarSpec)
                assertFalse(TvBoxSpiderPolicy.mayRun(false,prefs.tvBoxWarehouseUrl,"new.jar",trustedRoot,trustedJar))
            } finally { prefs.tvBoxWarehouseUrl = previous }
            assertEquals(200, post("/end", "{}", session).first)
            try { assertEquals(403, post("/status", "{}", session).first) }
            catch (_: java.io.IOException) { assertTrue(server.pairing.expired()) }
        } finally { server.end() }
    }
    @Test fun untrustedEphemeralCertificateIsRejectedByDefaultClient() {
        val server = LanConfigServer(InstrumentationRegistry.getInstrumentation().targetContext, "127.0.0.1") { }
        try {
            server.start(5000, true)
            assertTrue(server.qr.startsWith("https://127.0.0.1:"))
            assertEquals(95, server.fingerprint.length)
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url(server.origin).build()).execute().close()
                fail("A self-signed certificate must not be silently trusted")
            } catch (_: javax.net.ssl.SSLException) { }
        } finally { server.end() }
    }
}
