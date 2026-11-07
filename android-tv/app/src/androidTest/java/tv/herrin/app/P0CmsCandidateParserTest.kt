package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Exercise the automatic CMS candidate path, not only a direct parser unit call. */
class P0CmsCandidateParserTest {
    @Test fun passiveConfiguredParserUsesParseJobThenRealMediaBytesAndDecoderFrames(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val bytes=inst.context.assets.open("synthetic.mp4").use{it.readBytes()}
        MockWebServer().use{server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=when{
                request.path?.startsWith("/parse?url=https://watch.example.test/movie.html")==true->
                    MockResponse().setHeader("Content-Type","application/json").setBody("""{"url":"http://127.0.0.1:${server.port}/media.mp4"}""")
                request.path?.startsWith("/media.mp4")==true->MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes))
                else->MockResponse().setResponseCode(404)
            }}
            server.start()
            val site=TvBoxSite("original fixture",false,apiUrl=server.url("/cms").toString())
            val source=HerrinTvBoxCmsSource(site,TvBoxVodRepository(),true)
            val film=Film("fixture","Original synthetic movie","","电影","2025","",0,playbackEpisodes=listOf(
                PlaybackEpisode(1,"正片",listOf(PlaybackLine("line","official","https://watch.example.test/movie.html")))))
            val config=TvBoxConfig.Single(listOf(site),emptyList(),false,jsonParsers=listOf(TvBoxJsonParse("passive JSON",server.url("/parse?url=").toString())))
            val result=runBlocking{withTimeout(6000){resolveCmsCandidate(inst.targetContext,source,film,1,"line",config,true)}}
            assertTrue(result is PlaybackResult.Ready)
            val media=(result as PlaybackResult.Ready).media
            assertEquals(server.url("/media.mp4").toString(),media.uri)
            assertTrue(runBlocking{HerrinRemoteMediaProbe(authorizeLocal={it==media.uri},strictHttpFailure=true).canRead(media)})
            ActivityScenario.launch<PlaybackTestActivity>(Intent(inst.targetContext,PlaybackTestActivity::class.java).putExtra("fixture",media.uri)).use{scenario->
                var frames=0;var failure:String?=null
                val end=System.currentTimeMillis()+20_000
                while(frames<4 &&failure==null &&System.currentTimeMillis()<end){
                    scenario.onActivity{frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0;failure=it.player.playerError?.errorCodeName}
                    if(frames<4)Thread.sleep(80)
                }
                assertNull(failure);assertTrue("Automatic CMS candidate must decode actual video",frames>=4)
            }
        }
    }
    @Test fun parserAuthenticationAndRateLimitRemainTypedInsteadOfMissingFilm()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(code in listOf(401,403,429))MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","90"));server.start()
            val site=TvBoxSite("fixture",false,apiUrl=server.url("/cms").toString())
            val film=Film("fixture","movie","","电影","2025","",0,playbackEpisodes=listOf(
                PlaybackEpisode(1,"正片",listOf(PlaybackLine("line","official","https://watch.example.test/movie.html")))))
            val config=TvBoxConfig.Single(listOf(site),emptyList(),false,jsonParsers=listOf(TvBoxJsonParse("JSON",server.url("/parse?url=").toString())))
            try{withTimeout(6000){resolveCmsCandidate(context,HerrinTvBoxCmsSource(site,TvBoxVodRepository(),true),film,1,"line",config,true)}
                fail("HTTP $code must not be classified as no matching film")}
            catch(error:SourceHttpFailure){assertEquals(code,error.code);assertEquals(90_000L,error.retryAfterMs)}
            assertEquals(1,server.requestCount)
        }
    }
}
