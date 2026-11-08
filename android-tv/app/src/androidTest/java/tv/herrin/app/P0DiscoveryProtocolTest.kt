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
        } finally { bridge.close();file.delete() }
    }
}
