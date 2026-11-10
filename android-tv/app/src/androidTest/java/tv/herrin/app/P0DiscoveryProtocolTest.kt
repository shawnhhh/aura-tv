package tv.herrin.app

import android.app.ActivityManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Original authorized test DEX through real remote process IPC. Not a live-provider test. */
class P0DiscoveryProtocolTest {
    @Test fun twoSpiderSitesRetainHitsAndRestoreExactVodAfterWorkerDeath() = runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val bytes=inst.context.assets.open("m467-spider.jar").use{it.readBytes()}
        val hash=MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}
        val file=File(File(context.codeCacheDir,"herrin-spider").apply{mkdirs()},"$hash.jar")
        file.delete();file.writeBytes(bytes);check(file.setReadOnly())
        val jar="https://example.invalid/original-fixture.jar;md5;$hash"
        val first=TvBoxSite("Original A",true,key="discovery-a",spiderApi="csp_Fixture")
        val second=first.copy(name="Original B",key="discovery-b")
        val config=TvBoxConfig.Single(listOf(first,second),emptyList(),true,spiderJar=jar)
        val bridge=TvBoxSpiderBridge(context)
        try {
            val nodes=HerrinDiscoveryNodes.create(emptyList(),emptyList(),config.sites,"fixture-root",config,
                HerrinFilmIdentity(99999,"movie",listOf("Original synthetic fixture"),null),
                TvBoxVodRepository(),true,bridge,bridge.configurationTicket())
            var result=HerrinSourceDiscovery.Snapshot("",0)
            HerrinSourceDiscovery().search("fixture-config",nodes,nodes.first().id,Mutex()){result=it}
            assertEquals(2,result.checked)
            assertEquals(2,result.hits.size)
            assertEquals(2,result.hits.map{it.key}.distinct().size)
            assertTrue(result.hits.all{it.movie.film.playbackEpisodes.isEmpty()})
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pid=manager.runningAppProcesses.orEmpty().single{it.processName=="${context.packageName}:spider"}.pid
            android.os.Process.killProcess(pid)
            withTimeout(5000){while(manager.runningAppProcesses.orEmpty().any{it.pid==pid})delay(25)}
            val hit=result.hits.first{it.node.site?.key==first.key}
            val loaded=withTimeout(15000){hit.node.details(hit.movie)}
            assertNotNull(loaded)
            assertEquals(hit.movie.film.spiderVodId,loaded!!.film.spiderVodId)
            assertEquals(first.key,loaded.film.spiderOrigin?.site)
            assertTrue(loaded.film.playbackEpisodes.isNotEmpty())
            assertEquals(2,result.hits.size)
            assertTrue(bridge.isApprovedSourceFilm(loaded.film))
            val entry=HerrinPlaybackEntry.firstPlayable(loaded.film)!!
            val resolved=bridge.play(loaded.film,entry.first,entry.second,true) as? PlaybackResult.Ready
                ?:throw AssertionError("Aggregated provider VOD must resolve through its own FongMi playerContent")
            assertTrue(HerrinRemoteMediaProbe(authorizeLocal={candidate->
                tv.herrin.app.nativeplayback.FongMiSessionAuthorization.isApproved(candidate,first.key)
            }).canRead(resolved.media))
            androidx.test.core.app.ActivityScenario.launch<PlaybackTestActivity>(android.content.Intent(context,
                PlaybackTestActivity::class.java).putExtra("fixture",resolved.media.uri)).use { scenario ->
                var frames=0;var failure:String?=null
                withTimeout(20_000) {
                    while(frames<4 &&failure==null) {
                        scenario.onActivity { frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0
                            failure=it.player.playerError?.errorCodeName }
                        if(frames<4)delay(80)
                    }
                }
                assertNull(failure)
                assertTrue("Aggregated/restarted original Spider must decode actual video",frames>=4)
            }
        } finally { bridge.close();file.delete() }
    }
    @Test fun httpSpiderOpaqueEpisodeGoesThroughFongmiAndRendersActualFrames() = runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val video=inst.context.assets.open("synthetic.mp4").use{it.readBytes()}
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val mediaCalls=java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                    if(request.requestUrl!!.encodedPath == "/video.mp4") {
                        mediaCalls.incrementAndGet()
                        return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","video/mp4")
                            .setBody(okio.Buffer().write(video))
                    }
                    check(request.requestUrl!!.queryParameter("play") == "opaque-episode-7")
                    check(request.requestUrl!!.queryParameter("flag") == "Original line")
                    return okhttp3.mockwebserver.MockResponse().setBody(org.json.JSONObject()
                        .put("parse",0).put("url",server.url("/video.mp4")).toString())
                }
            }
            server.start()
            val site=TvBoxSite("Original HTTP fixture",false,server.url("/api").toString(),key="http-fixture",apiType=4)
            val source=Film("provider-film","Original fixture","","电影","2026","",R.drawable.photofilm_orbit,
                cmsVodId="provider-vod-3",playbackEpisodes=listOf(PlaybackEpisode(1,"First",
                    listOf(PlaybackLine("cms-0","Original line","opaque-episode-7")))))
            val parsed=resolveHttpSpiderPlayback(inst.targetContext,site,source,1,"cms-0",null,true)
            assertTrue(parsed is PlaybackResult.Ready)
            assertEquals(0,mediaCalls.get())
            val media=(parsed as PlaybackResult.Ready).media
            assertTrue(HerrinRemoteMediaProbe(authorizeLocal={it==server.url("/video.mp4").toString()}).canRead(media))
            androidx.test.core.app.ActivityScenario.launch<PlaybackTestActivity>(android.content.Intent(inst.targetContext,
                PlaybackTestActivity::class.java).putExtra("fixture",media.uri)).use { scenario ->
                var frames=0;var failure:String?=null
                withTimeout(20_000) {
                    while(frames<4 && failure==null) {
                        scenario.onActivity { frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0
                            failure=it.player.playerError?.errorCodeName }
                        if(frames<4)delay(80)
                    }
                }
                assertNull(failure)
                assertTrue("Actual original synthetic MP4 must decode",frames>=4)
            }
        }
    }

    @Test fun xmlCmsUsesActualAndroidDomWithoutAllowingDtdOrExternalEntities() {
        val xml="<rss><list><video><id>platform-7</id><name>Original fixture</name><dl><dd flag=\"A\">One${'$'}https://example.invalid/video.mp4</dd></dl></video></list></rss>"
        val film=TvBoxVodCodec.decode(TvBoxCmsXml.toJson(xml)).single()
        assertEquals("platform-7",film.cmsVodId)
        assertEquals("A",film.playbackEpisodes.single().lines.single().title)
        val dtd="<!DOCTYPE rss [<!ENTITY ex SYSTEM 'file:///data/local/tmp/must-not-read'>]><rss><video><id>1</id><name>&ex;</name></video></rss>"
        assertTrue(runCatching{TvBoxCmsXml.toJson(dtd)}.isFailure)
    }

    @Test fun expandedWarehouseCredentialsStayInMemoryAndContinuationDoesNotRefetch() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        HerrinDiscoveryWarehouses.clear()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val root=server.url("/p0-private-expansion.json").toString()
            val file=File(File(context.filesDir,"tvbox-config"),SourceHealthIndex.digest(root)+".json")
            assertFalse(file.exists())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"sites":[{"name":"Original","key":"private","type":4,"api":"https://example.invalid/api","header":{"Cookie":"fixture-only-cookie"},"ext":"fixture-only-token"}]}"""))
            val repo=TvBoxConfigRepository(context)
            repeat(2) {
                assertEquals(0,HerrinDiscoveryWarehouses.expand(listOf(TvBoxWarehouse("private",root)),repo,true,
                    create={_,owner->
                        assertEquals("fixture-only-cookie",owner.sites.single().headers["Cookie"])
                        emptyList()
                    },emit={}))
                assertFalse("Expanded authorization must not be written to a plaintext config cache",file.exists())
            }
            assertEquals(1,server.requestCount)
            assertNotNull(HerrinDiscoveryWarehouses.cached(root))
        }
        HerrinDiscoveryWarehouses.clear()
    }

}
