package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual loopback HTTP delays and connection loss, not a TMDB/TCL timing claim. */
class TmdbControlledNetworkTest {
    @Test fun delayedHttpPublishesMetadataBeforeProofWithBoundedConnectionLoss(): Unit = runBlocking {
        val fixture = TmdbBrowseFixture()
        val previews = mutableListOf<Long>()
        val finished = mutableListOf<Long>()
        val raw = JSONArray()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(1,TimeUnit.SECONDS).readTimeout(1,TimeUnit.SECONDS)
            .callTimeout(2,TimeUnit.SECONDS).build()
        MockWebServer().use { server ->
            val loseOne = AtomicBoolean(false)
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (Regex("/3/(movie|tv)/[0-9]+.*").matches(path) && loseOne.compareAndSet(true,false))
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    return MockResponse().setHeader("Content-Type","application/json")
                        .setHeadersDelay(30,TimeUnit.MILLISECONDS)
                        .setBody(fixture.load(TmdbEndpoints.PRIMARY.removeSuffix("/3") + path,"fixture"))
                }
            }
            server.start()
            repeat(10) { sample ->
                loseOne.set(sample % 2 == 1)
                val repo = TmdbBrowseRepository(ArtworkJsonFetcher { url,_ ->
                    val uri = java.net.URI(url)
                    client.newCall(Request.Builder().url(server.url(uri.rawPath + "?" + uri.rawQuery)).build())
                        .execute().use { response -> check(response.isSuccessful); response.body!!.string() }
                })
                val start = System.nanoTime()
                var first = -1L
                val result = repo.load(TmdbRailSpec(TmdbBrowsePage.HOME,TmdbRailKind.HOT),1,"fixture", onPreview={ batch ->
                    if (first < 0) first = (System.nanoTime()-start)/1_000_000
                    assertTrue(batch.entries.isNotEmpty())
                    assertTrue(batch.entries.all { it.availability.state == TmdbAvailability.UNKNOWN })
                })
                val end = (System.nanoTime()-start)/1_000_000
                assertTrue(result.entries.isNotEmpty())
                assertTrue("Preview must precede completion of delayed proofs", first in 0 until end)
                previews += first; finished += end
                raw.put(JSONObject().put("preview_ms",first).put("proof_complete_ms",end).put("connection_loss",sample % 2 == 1))
            }
        }
        fun percentile(values: List<Long>, fraction: Double) = values.sorted()[(kotlin.math.ceil(values.size*fraction).toInt()-1).coerceAtLeast(0)]
        println("HERRIN_TMDB_CONTROLLED=" + JSONObject().put("scope","loopback_http_30ms_headers_and_one_lost_connection_not_IP_packet_loss")
            .put("n",10).put("samples",raw)
            .put("preview_p50_ms",percentile(previews,0.5)).put("preview_p95_ms",percentile(previews,0.95))
            .put("proof_complete_p50_ms",percentile(finished,0.5)).put("proof_complete_p95_ms",percentile(finished,0.95)))
    }
}
