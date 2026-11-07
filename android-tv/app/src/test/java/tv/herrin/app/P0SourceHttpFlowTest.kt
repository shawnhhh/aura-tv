package tv.herrin.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Real sockets + actual synthetic media response bytes. This is controlled loopback, NOT a live provider benchmark. */
class P0SourceHttpFlowTest {
    private val request=HerrinFilmIdentity(550,"movie",listOf("受控影片","Controlled Film"),2025)
    @Test fun actualCmsDiscoveryDetailsAndMediaBytesReachNodesOneThreeSevenEightAcrossSixTwelveFortyFive()=runBlocking {
        val video=File("src/androidTest/assets/synthetic.mp4").readBytes()
        assertTrue(video.size>1024)
        for(count in listOf(6,12,45))for(hit in listOf(1,3,7,8).filter{it<=count}){
            val mediaReads=AtomicInteger();val first=AtomicLong(-1);val candidate=AtomicLong(-1);val media=AtomicLong(-1)
            MockWebServer().use{server->
                server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
                    val url=r.requestUrl!!
                    if(url.encodedPath=="/video"){
                        mediaReads.incrementAndGet()
                        return MockResponse().setResponseCode(206).setHeader("Content-Type","video/mp4")
                            .setHeader("Content-Range","bytes 0-1023/${video.size}").setBody(Buffer().write(video,0,1024))
                    }
                    if(count>=12 &&url.encodedPath=="/node6")return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    if(url.encodedPath!="/node$hit")return MockResponse().setBody("{\"list\":[]}")
                    assertEquals("fixture-only",r.getHeader("Authorization"))
                    val film=JSONObject().put("vod_id","42").put("vod_name","受控影片").put("vod_year","2025")
                        .put("type_name","电影").put("vod_play_from","1080p")
                        .put("vod_play_url","正片$"+server.url("/video"))
                    return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("list",JSONArray().put(film).put(film)).toString())
                }}
                server.start()
                val repo=TvBoxVodRepository()
                val adapters=(1..count).map{HerrinTvBoxCmsSource(TvBoxSite("fixture-$it",false,apiUrl=server.url("/node$it").toString(),
                    headers=mapOf("Authorization" to "fixture-only")),repo,true)}
                val index=adapters.associateBy{it.id}
                val probe=HerrinRemoteMediaProbe(authorizeLocal={it==server.url("/video").toString()},strictHttpFailure=true)
                val at=System.nanoTime()
                fun elapsed()=(System.nanoTime()-at)/1_000_000.0
                val result=withTimeout(8_000){HerrinVerifiedSourceResolver(adapters).find(request,
                    personalPlayback={PlaybackResult.Unavailable("no personal fixture")},streaming=true,
                    onEvent={event->
                        if(event.outcome==SourceSearchOutcome.CHECKING)first.compareAndSet(-1,System.nanoTime())
                        if(event.stage==SourceSearchStage.SEARCH &&event.outcome==SourceSearchOutcome.VERIFIED)candidate.compareAndSet(-1,System.nanoTime())
                        if(event.stage==SourceSearchStage.MEDIA &&event.outcome==SourceSearchOutcome.VERIFIED)media.compareAndSet(-1,System.nanoTime())
                    },verifyRemote={offer,film->
                        val selected=HerrinPlaybackEntry.firstPlayable(film)!!
                        val ready=index.getValue(offer.sourceId).play(film,selected.first,selected.second) as PlaybackResult.Ready
                        probe.canRead(ready.media)
                    },remoteDetails={offer->(index.getValue(offer.sourceId).details(offer) as? TvBoxVodDetailResult.Ready)?.film})}
                assertEquals(adapters[hit-1].id,result.lookup.options.single().sourceId)
                assertEquals(1,mediaReads.get()) // duplicate VOD IDs never cause duplicate verification
                assertTrue(candidate.get()>=first.get());assertTrue(media.get()>=candidate.get())
                val timing=JSONObject().put("scope","controlled_loopback_not_live_provider").put("nodes",count).put("hit",hit)
                    .put("search_callback_ms",(first.get()-at)/1_000_000.0).put("candidate_ms",(candidate.get()-at)/1_000_000.0)
                    .put("verified_ms",(media.get()-at)/1_000_000.0).put("total_ms",elapsed())
                println("HERRIN_CONTROLLED_SOURCE="+timing)
                withTimeout(1500){while(SourceHttpWork.active.get()!=0)delay(10)}
            }
        }
    }
    @Test fun cancellingAStalledHttpBodyClosesTheActualCallAndLeavesNoRunningWork()=runBlocking {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.start()
            val client=OkHttpClient()
            val job=async{readSourceResponse(client,Request.Builder().url(server.url("/stall")).build()){it.body!!.string()}}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))}
            job.cancelAndJoin()
            withTimeout(1500){while(SourceHttpWork.active.get()!=0 ||client.dispatcher.runningCallsCount()!=0)delay(10)}
            assertEquals(0,SourceHttpWork.active.get());assertEquals(0,client.dispatcher.runningCallsCount())
        }
    }
    @Test fun authenticationAndRateLimitStopAliasesAndRespectRetryAfterWithoutMediaProbe()=runBlocking {
        for(code in listOf(401,403,429))MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","120"));server.start()
            val source=HerrinTvBoxCmsSource(TvBoxSite("fixture",false,apiUrl=server.url("/api").toString()),TvBoxVodRepository(),true)
            val health=SourceHealthIndex(null,"fixture-scope")
            val result=HerrinFilmRouter(listOf(source)).find(request,streaming=true,health=health)
            assertTrue(result.options.isEmpty());assertFalse(health.eligible(source.id))
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun strictMediaAuthenticationDoesNotTryAnotherSignedUrlAndExpiryIsClassified()=runBlocking {
        for(code in listOf(401,403,404,410,429))MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","90"));server.start()
            val probe=HerrinRemoteMediaProbe(authorizeLocal={it==server.url("/media").toString()},strictHttpFailure=true)
            try{probe.canRead(PlaybackMedia(server.url("/media").toString()));fail("HTTP $code must remain a typed failure")}
            catch(error:SourceHttpFailure){assertEquals(code,error.code);assertEquals(90_000L,error.retryAfterMs)}
        }
    }
}
