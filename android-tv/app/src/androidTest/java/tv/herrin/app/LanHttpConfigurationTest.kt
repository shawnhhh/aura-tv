package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Legacy test identity retained: plaintext now cannot reach any configuration endpoint. */
class LanHttpConfigurationTest {
    @Test fun cleartextCannotSubmitSourcesOrSensitiveCredentialsAndSocketCloses() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=Preferences(context)
        val before=listOf(prefs.tmdbReadToken,prefs.tvBoxWarehouseUrl,prefs.nasWebDavUrl,
            prefs.tvBoxTrustedSpiderRootUrl,prefs.tvBoxTrustedSpiderJarSpec)
        val credentials=HerrinPersonalCredentialStore(context,"nas").load()
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        val server=LanConfigServer(context,"127.0.0.1"){events.add(it)}
        val client=OkHttpClient.Builder().callTimeout(2,java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            server.start(5000,true)
            val plain=server.origin.replace("https:","http:")
            val body=JSONObject().put("token","sensitive").put("username","account").put("password","secret")
                .put("url","http://饭太硬.net/tv").toString()
            for(path in listOf("/pair","/sources","/entries/save","/entries/batch","/tmdb","/nas","/live")) {
                try {
                    client.newCall(Request.Builder().url(plain+path).header("Origin",plain)
                        .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use {
                        fail("Plaintext reached a TLS-only endpoint: $path")
                    }
                }catch(_:java.io.IOException) { }
            }
            assertTrue(events.isEmpty())
            assertFalse(server.pairing.pending())
            assertEquals(before,listOf(prefs.tmdbReadToken,prefs.tvBoxWarehouseUrl,prefs.nasWebDavUrl,
                prefs.tvBoxTrustedSpiderRootUrl,prefs.tvBoxTrustedSpiderJarSpec))
            assertEquals(credentials,HerrinPersonalCredentialStore(context,"nas").load())
        }finally{server.end()}
        assertFalse(server.isAlive)
        try {
            client.newCall(Request.Builder().url(server.origin.replace("https:","http:")).build()).execute().close()
            fail("Listener must close")
        }catch(_:java.io.IOException) { }
    }
    @Test fun wildcardAndPublicListenersAreRejected() {
        for(host in listOf("0.0.0.0","8.8.8.8","::")) {
            try{LanConfigServer(InstrumentationRegistry.getInstrumentation().targetContext,host){};fail("Public bind")}
            catch(_:IllegalArgumentException) { }
        }
    }
}
