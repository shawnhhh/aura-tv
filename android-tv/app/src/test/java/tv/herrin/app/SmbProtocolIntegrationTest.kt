package tv.herrin.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Mandatory CI protocol tests against authenticated, signed Samba shares; no mocked successful SMB bytes. */
class SmbProtocolIntegrationTest {
    private fun account(port:Int=1445,password:String="OriginalFixtureOnly123",encryption:Boolean=port==1445)=
        HerrinSmbAccount("smb://127.0.0.1:$port/media","herrinfixture",password,encryption=encryption)
    private fun originalMedia():ByteArray {
        val file=listOf(Paths.get("src/androidTest/assets/synthetic.mp4"),Paths.get("app/src/androidTest/assets/synthetic.mp4"))
            .first{Files.isRegularFile(it)}
        return Files.readAllBytes(file)
    }
    private val client=OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build()
    @Test fun realSmb2AndEncryptedSmb3ReadTheOriginalMedia() {
        val expected=originalMedia()
        for(port in listOf(1445,1446)) {
            SmbAccess(account(port)).open("Original SMB.2026.mp4").use { file ->
                assertEquals(expected.size.toLong(),file.size)
                val bytes=ByteArray(128)
                assertEquals(128,file.read(bytes,0,0,128))
                assertArrayEquals(expected.copyOf(128),bytes)
            }
        }
    }
    @Test fun sourceSearchRangeSeekAndLargeFileUseTheActualSmbServer():Unit=runBlocking {
        HerrinSmbSessions().use { sessions ->
            val source=HerrinSmbSource(account(),sessions)
            assertTrue(source.testConnection())
            val movie=source.search(HerrinFilmIdentity(123,"movie",listOf("Original SMB"),2026)).single()
            assertFalse(movie.film.toString().contains("OriginalFixtureOnly123"))
            val tv=source.search(HerrinFilmIdentity(124,"tv",listOf("Original Series"),null,1,1)).single()
            assertEquals(1,tv.season);assertEquals(1,tv.episode)
            val cloudCalls=AtomicInteger()
            val cloud=object:HerrinFilmSource {
                override val id="unneeded-cloud";override val type=HerrinSourceType.CLOUD
                override suspend fun search(identity:HerrinFilmIdentity):List<HerrinSourceMovie>{cloudCalls.incrementAndGet();error("NAS must win")}
            }
            val chosen=HerrinVerifiedSourceResolver(listOf(cloud,source)).find(
                HerrinFilmIdentity(123,"movie",listOf("Original SMB"),2026),
                personalPlayback=source::playback,
                verifyPersonal={HerrinPersonalMediaProbe().canRead(it)},remoteDetails={error("No TVBox lookup")})
            assertEquals(HerrinSourceTier.PERSONAL,chosen.lookup.selectedTier)
            assertEquals(source.id,chosen.lookup.options.first().sourceId)
            assertEquals(0,cloudCalls.get())
            val media=sessions.media(account(),"Original SMB.2026.mp4")
            assertTrue(media.headers.isEmpty());assertFalse(media.uri.contains("herrinfixture"))
            assertTrue(HerrinPersonalMediaProbe().canRead(media))
            assertTrue(HerrinRemoteMediaProbe(authorizeLocal={it==media.uri}).canRead(media))
            val large=sessions.media(account(),"Large.2026.mkv")
            client.newCall(Request.Builder().url(large.uri).header("Range","bytes=5368709120-").build()).execute().use {
                assertEquals(206,it.code);assertEquals("HERRIN-LARGE-SEEK-END",it.body!!.string())
                assertTrue(it.header("Content-Range")!!.startsWith("bytes 5368709120-"))
            }
            client.newCall(Request.Builder().url(media.uri).header("Origin","https://unrelated.example").build()).execute().use{assertEquals(403,it.code)}
            client.newCall(Request.Builder().url(media.uri).header("Range","bytes=999999999999-").build()).execute().use{assertEquals(416,it.code)}
        }
    }
    @Test fun aDroppedSmbReadReconnectsAtTheSameOffsetAndAllHandlesClose() {
        val dropped=AtomicBoolean();val opened=AtomicInteger();val closed=AtomicInteger()
        val sessions=HerrinSmbSessions { a,p ->
            val delegate=SmbAccess(a).open(p);opened.incrementAndGet()
            object:SmbReadHandle by delegate {
                private val ended=AtomicBoolean()
                override fun read(buffer:ByteArray,position:Long,offset:Int,length:Int):Int {
                    if(dropped.compareAndSet(false,true)){close();throw IOException("Controlled disconnect")}
                    return delegate.read(buffer,position,offset,length)
                }
                override fun close(){if(ended.compareAndSet(false,true)){closed.incrementAndGet();delegate.close()}}
            }
        }
        val media=sessions.media(account(),"Original SMB.2026.mp4")
        try {
            val original=originalMedia()
            client.newCall(Request.Builder().url(media.uri).header("Range","bytes=100-199").build()).execute().use {
                assertEquals(206,it.code);assertArrayEquals(original.copyOfRange(100,200),it.body!!.bytes())
            }
        }finally{sessions.close()}
        assertTrue(dropped.get());assertEquals(opened.get(),closed.get())
        assertThrows(IOException::class.java){client.newCall(Request.Builder().url(media.uri).build()).execute().close()}
    }
    @Test fun invalidAuthenticationDoesNotReturnUsableMediaOrLeakItsSecret() {
        val error=assertThrows(Exception::class.java){SmbAccess(account(password="wrong-fixture-password")).use{it.share()}}
        assertEquals(SourceSearchOutcome.AUTH_REQUIRED,SmbFailure.outcome(error))
        assertFalse(SmbFailure.message(error).contains("wrong-fixture-password"))
    }
    @Test fun cancelledNegotiationDoesNotLeaveAnUnboundedSearch():Unit=runBlocking {
        ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted=java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
            val ready=java.util.concurrent.CountDownLatch(1)
            val thread=Thread {runCatching{accepted.set(silent.accept());ready.countDown()}}.apply{isDaemon=true;start()}
            HerrinSmbSessions().use { sessions ->
                val source=HerrinSmbSource(HerrinSmbAccount("smb://127.0.0.1:${silent.localPort}/media"),sessions)
                val job=launch(Dispatchers.IO){source.search(HerrinFilmIdentity(123,"movie",listOf("Original"),null))}
                assertTrue(ready.await(2,TimeUnit.SECONDS))
                val start=System.nanoTime()
                withTimeout(2000){job.cancelAndJoin()}
                assertTrue((System.nanoTime()-start)/1_000_000<2000)
            }
            accepted.get()?.close();thread.join(500)
        }
    }
}
