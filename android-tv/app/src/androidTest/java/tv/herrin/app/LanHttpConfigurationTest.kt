package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanHttpConfigurationTest {
    @Test fun publicSourcesOnlyAndSocketCloses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Preferences(context)
        val oldUrl = prefs.tvBoxWarehouseUrl
        val rawPrefs = context.getSharedPreferences("herrin", 0)
        val oldExtra = rawPrefs.getString("services_sources", null)
        val oldRoot = prefs.tvBoxTrustedSpiderRootUrl
        val oldJar = prefs.tvBoxTrustedSpiderJarSpec
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = LanConfigServer(context, "127.0.0.1", quickHttp = true) { events.add(it) }
        val client = OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
        var endpoint = ""
        try {
            rawPrefs.edit().remove("services_sources").commit()
            server.start(5000, true)
            endpoint = server.origin
            assertTrue(server.qr.startsWith("http://127.0.0.1:"))
            client.newCall(Request.Builder().url(endpoint).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("不能防止局域网中间人攻击"))
            }
            fun post(path: String, input: JSONObject = JSONObject(), key: String = ""): Pair<Int, String> =
                client.newCall(Request.Builder().url(endpoint + path).header("Origin", endpoint)
                    .header("X-Herrin-Session", key)
                    .post(input.toString().toRequestBody("application/json".toMediaType())).build())
                    .execute().use { it.code to it.body!!.string() }
            val pair = JSONObject().put("pairing", server.pairing.pairing)
            assertTrue(JSONObject(post("/pair", pair).second).getBoolean("pending"))
            assertEquals(403, post("/sources", JSONObject().put("url", "http://饭太硬.net/tv")).first)
            server.pairing.confirm()
            val key = JSONObject(post("/pair", pair).second).getString("session")
            assertEquals(200, post("/sources", JSONObject().put("url", "http://饭太硬.net/tv"), key).first)
            assertEquals(TvBoxUrl.normalize("http://饭太硬.net/tv", true), prefs.tvBoxWarehouseUrl)
            assertEquals(listOf("sources"), events.toList())
            assertEquals(200, post("/entries/batch", JSONObject().put("urls",
                "http://饭太硬.net/a\nhttps://example.org/b\nhttp://饭太硬.net/a"), key).first)
            assertEquals(2, AdditionalServiceConfigs(context).list("sources").size)
            val entry = JSONObject().put("module", "sources").put("name", "公开源").put("url", "http://example.org/c")
            assertEquals(200, post("/entries/save", entry, key).first)
            assertEquals(400, post("/entries/save", JSONObject(entry.toString()).put("password", "secret"), key).first)
            for (path in listOf("/tmdb", "/tmdb/test", "/nas", "/nas/test", "/live", "/status",
                "/catalogs", "/catalogs/save", "/catalogs/activate", "/entries/delete", "/unknown")) {
                assertEquals(path, 400, post(path, JSONObject().put("token", "secret"), key).first)
            }
            for (module in listOf("nas", "live", "cloud")) {
                assertEquals(400, post("/entries", JSONObject().put("module", module), key).first)
            }
            for (url in listOf("http://user:password@example.org/a", "http://example.org/a?key=secret",
                "http://example.org/token/secret", "javascript:alert(1)", "http://example.org/a#secret")) {
                assertEquals(url, 400, post("/sources", JSONObject().put("url", url), key).first)
            }
            assertEquals(oldRoot, prefs.tvBoxTrustedSpiderRootUrl)
            assertEquals(oldJar, prefs.tvBoxTrustedSpiderJarSpec)
            assertFalse(TvBoxSpiderPolicy.mayRun(false, prefs.tvBoxWarehouseUrl, "new.jar", oldRoot, oldJar))
            assertEquals(200, post("/end", key = key).first)
            assertTrue(server.pairing.expired())
        } finally {
            server.end()
            prefs.tvBoxWarehouseUrl = oldUrl
            val edit = rawPrefs.edit()
            if (oldExtra == null) edit.remove("services_sources") else edit.putString("services_sources", oldExtra)
            edit.commit()
        }
        try {
            client.newCall(Request.Builder().url(endpoint).build()).execute().close()
            fail("Listener must be closed")
        } catch (_: java.io.IOException) { }
    }

    @Test fun wildcardListenerIsRejected() {
        try {
            LanConfigServer(InstrumentationRegistry.getInstrumentation().targetContext, "0.0.0.0", true) {}
            fail("Wildcard bind must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
