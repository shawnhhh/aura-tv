package tv.herrin.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real SMB3 encryption -> offset-aware loopback -> the unchanged FongMi PlayerManager -> decoded frames + Seek. */
class SmbRealPlaybackTest {
    @Test fun authenticatedNasSearchDecodesOriginalMp4AndSeeksWithTheExistingPlayer():Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val account=HerrinSmbAccount("smb://10.0.2.2:1445/media","herrinfixture","OriginalFixtureOnly123",encryption=true)
        HerrinSmbSessions().use { sessions ->
            val source=HerrinSmbSource(account,sessions)
            assertTrue(source.testConnection())
            val offers=source.search(HerrinFilmIdentity(123,"movie",listOf("Original SMB"),2026))
            assertTrue(offers.isNotEmpty())
            val media=(source.playback(offers.first()) as PlaybackResult.Ready).media
            assertTrue(verifySelectedPersonalMedia(media) is PlaybackResult.Ready)
            assertTrue(media.headers.isEmpty())
            ActivityScenario.launch<PlaybackTestActivity>(Intent(inst.targetContext,PlaybackTestActivity::class.java).putExtra("fixture",media.uri)).use { scenario ->
                var frames=0;var error:String?=null;var duration=0L
                val deadline=System.currentTimeMillis()+25000
                while(System.currentTimeMillis()<deadline && frames<4 && error==null) {
                    scenario.onActivity {frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0;error=it.player.playerError?.errorCodeName;duration=it.player.duration}
                    if(frames<4)Thread.sleep(80)
                }
                assertNull(error);assertTrue("SMB must really decode frames",frames>=4)
                assertTrue(duration>1000)
                val target=duration/2
                scenario.onActivity{it.player.pause();it.player.seekTo(target)}
                var position=0L
                val seekDeadline=System.currentTimeMillis()+8000
                while(System.currentTimeMillis()<seekDeadline && kotlin.math.abs(position-target)>300) {
                    scenario.onActivity{position=it.player.currentPosition;error=it.player.playerError?.errorCodeName}
                    Thread.sleep(60)
                }
                assertNull(error);assertTrue("SMB Seek must reach the requested position",kotlin.math.abs(position-target)<=300)
                scenario.onActivity{it.player.play()}
                var later=frames
                val resumeDeadline=System.currentTimeMillis()+10000
                while(System.currentTimeMillis()<resumeDeadline && later<=frames) {
                    scenario.onActivity{later=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0;error=it.player.playerError?.errorCodeName}
                    Thread.sleep(60)
                }
                assertNull(error);assertTrue("Frames continue after Seek",later>frames)
            }
        }
        android.util.Log.i("HerrinSmbEvidence","real_smb3=1 encryption=1 decoded_frames=1 seek=1 physical_nas=0 physical_tcl=0")
    }
    @Test fun originalMkvTsAndM2tsDecodeThroughTheSameSmbAndFongMiChain():Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val account=HerrinSmbAccount("smb://10.0.2.2:1445/media","herrinfixture","OriginalFixtureOnly123",encryption=true)
        HerrinSmbSessions().use { sessions ->
            for((name,extension) in listOf("Original MKV" to "mkv","Original TS" to "ts","Original M2TS" to "m2ts")) {
                val source=HerrinSmbSource(account,sessions)
                val offers=source.search(HerrinFilmIdentity(123,"movie",listOf(name),2026))
                assertTrue("SMB media file must be found: $extension",offers.isNotEmpty())
                val media=(source.playback(offers.first()) as PlaybackResult.Ready).media
                ActivityScenario.launch<PlaybackTestActivity>(Intent(inst.targetContext,PlaybackTestActivity::class.java).putExtra("fixture",media.uri)).use { scenario ->
                    var frames=0;var error:String?=null
                    val deadline=System.currentTimeMillis()+20000
                    while(System.currentTimeMillis()<deadline && frames<2 && error==null) {
                        scenario.onActivity{frames=it.player.videoDecoderCounters?.renderedOutputBufferCount?:0;error=it.player.playerError?.errorCodeName}
                        if(frames<2)Thread.sleep(80)
                    }
                    assertNull("SMB $extension decode error",error)
                    assertTrue("SMB $extension must decode real frames",frames>=2)
                }
            }
        }
    }

}
