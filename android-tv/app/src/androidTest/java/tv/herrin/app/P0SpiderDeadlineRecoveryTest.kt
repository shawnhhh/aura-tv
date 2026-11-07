package tv.herrin.app

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real Messenger/DEX/OS process recovery; the fixture deliberately ignores Thread.interrupt(). */
class P0SpiderDeadlineRecoveryTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun cacheJar(context:Context):Pair<File,String>{
        val bytes=inst.context.assets.open("m467-spider.jar").use{it.readBytes()}
        val hash=MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}
        val file=File(File(context.codeCacheDir,"herrin-spider").apply{mkdirs()},"$hash.jar")
        file.delete();file.writeBytes(bytes);check(file.setReadOnly())
        return file to "https://example.invalid/original-fixture.jar;md5;$hash"
    }
    @Test fun searchActivationNeverCallsHomeAndStillPublishesSignedSearchResults()=runBlocking {
        val state=ConcurrentHashMap<String,Any>().apply{listOf("inits","destroys","homes","searches","bootstraps").forEach{put(it,AtomicInteger())}}
        val context=object:ContextWrapper(inst.targetContext){override fun getSystemService(name:String):Any?=
            if(name=="herrin.fixture.lifecycle")state else super.getSystemService(name)}
        val (file,jar)=cacheJar(context)
        val site=TvBoxSite("original",true,key="p0-search-only",spiderApi="csp_Fixture")
        val config=TvBoxConfig.Single(listOf(site),emptyList(),true,spiderJar=jar)
        val worker=TvBoxSpiderWorker(context)
        try{
            val activated=worker.browse(site,config,loadHome=false) as SpiderResult.Ready
            assertTrue(activated.value.isEmpty());assertEquals(0,(state["homes"] as AtomicInteger).get())
            val found=worker.search(site,"Original synthetic fixture") as SpiderResult.Ready
            assertEquals(1,found.value.size)
            assertTrue(worker.details(found.value.single()) is SpiderResult.Ready)
        }finally{worker.close();file.delete()}
    }
    @Test fun uninterruptibleSixthNodeIsReapedAndSeventhGetsFreshProofProxyAndDecodedFrames()=runBlocking {
        val context=inst.targetContext
        val (file,jar)=cacheJar(context)
        val marker=File(context.filesDir,"p0-fixture-hang-entered").apply{delete()}
        val sixth=TvBoxSite("original sixth",true,key="p0-sixth",spiderApi="csp_Fixture")
        val seventh=sixth.copy(key="p0-seventh")
        val config=TvBoxConfig.Single(listOf(sixth,seventh),emptyList(),true,spiderJar=jar)
        val bridge=TvBoxSpiderBridge(context)
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun child()=manager.runningAppProcesses.orEmpty().firstOrNull{it.processName=="${context.packageName}:spider"}?.pid
        try{
            assertTrue(bridge.browse(sixth,config,searchOnly=true) is SpiderResult.Ready)
            val old=(bridge.search(sixth,"Original synthetic fixture") as SpiderResult.Ready).value.single()
            val oldDetail=(bridge.details(old) as SpiderResult.Ready).value
            val oldPid=requireNotNull(child());assertNotEquals(android.os.Process.myPid(),oldPid)
            val mainPid=android.os.Process.myPid()
            val hung=async(Dispatchers.Default){withTimeoutOrNull(1200){withContext(SpiderRequestBudget(4000)){
                bridge.search(sixth,"__HERRIN_TEST_IGNORE_INTERRUPT__")
            }}}
            withTimeout(1000){while(!marker.exists())delay(10)}
            assertNull(hung.await())
            assertEquals(0,withContext(Dispatchers.Main.immediate){bridge.pendingSearchRequests()})
            withTimeout(5000){while(child()==oldPid)delay(25)}
            assertEquals(mainPid,android.os.Process.myPid())
            assertTrue(withTimeout(6000){bridge.browse(seventh,config,searchOnly=true)} is SpiderResult.Ready)
            assertNotEquals(oldPid,child())
            assertTrue(bridge.details(oldDetail) is SpiderResult.Failure)
            assertTrue(bridge.play(oldDetail,1,"spider-0",true) is PlaybackResult.Unavailable)
            val found=(bridge.search(seventh,"Original synthetic fixture") as SpiderResult.Ready).value.single()
            val fresh=(bridge.details(found) as SpiderResult.Ready).value
            assertEquals(seventh.key,fresh.spiderOrigin?.site)
            val media=bridge.play(fresh,1,"spider-0",true) as? PlaybackResult.Ready
                ?:throw AssertionError("Fresh source must resolve under its own proxy authority")
            assertTrue(HerrinRemoteMediaProbe(authorizeLocal={tv.herrin.app.nativeplayback.FongMiSessionAuthorization.isApproved(it,seventh.key)}).canRead(media.media))
            ActivityScenario.launch<PlaybackTestActivity>(Intent(context,PlaybackTestActivity::class.java).putExtra("fixture",media.media.uri)).use{scenario->
                var frames=0;var failure:String?=null
                val end=System.currentTimeMillis()+20_000
                while(frames<4 &&failure==null &&System.currentTimeMillis()<end){
                    scenario.onActivity{frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0;failure=it.player.playerError?.errorCodeName}
                    if(frames<4)delay(80)
                }
                assertNull(failure);assertTrue("Fresh seventh node must decode actual synthetic video",frames>=4)
            }
            android.util.Log.i("HerrinSourceRecovery","uninterruptible_fixture_reaped=1 fresh_media_verified=1 old_proof_rejected=1 main_process_preserved=1 pending=0 physical_tcl=0")
            Unit
        }finally{bridge.close();marker.delete();file.delete()}
    }
}
