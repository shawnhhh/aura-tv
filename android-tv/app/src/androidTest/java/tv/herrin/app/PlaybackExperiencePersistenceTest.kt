package tv.herrin.app
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import com.fongmi.android.tv.bean.Track
import org.junit.Assert.*
import org.junit.Test
class PlaybackExperiencePersistenceTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Test fun realEndedMediaNotifiesNextOnceAndConsumerBackClosesOverlayFirst() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val f = java.io.File(inst.targetContext.cacheDir, "experience-original.mp4")
        inst.context.assets.open("synthetic.mp4").use { input -> f.outputStream().use { input.copyTo(it) } }
        try {
            val intent = android.content.Intent(inst.targetContext, PlaybackTestActivity::class.java)
                .putExtra("fixture", f.toURI().toString()).putExtra("continuousFixture", true).putExtra("consumerControls", true)
            androidx.test.core.app.ActivityScenario.launch<PlaybackTestActivity>(intent).use { scenario ->
                compose.waitUntil(30000) { var ready=false; scenario.onActivity { ready=(it.player.videoDecoderCounters?.renderedOutputBufferCount?:0)>3 };ready }
                scenario.onActivity { it.player.seekTo((it.player.duration-100).coerceAtLeast(0)) }
                compose.waitUntil(10000) { var ended=false;scenario.onActivity { ended=it.nextEpisodeRequests==1 };ended }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                scenario.onActivity { assertFalse(it.player.isReleased);assertEquals(1,it.nextEpisodeRequests) }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10000) { var closed=false;scenario.onActivity { closed=it.player.isReleased };closed }
            }
        } finally { f.delete() }
    }

    @Test fun historyRejectsLateWritersAndKeepsCompletionAcrossInstances() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val h=PlaybackHistory(c);val id="original-${java.util.UUID.randomUUID()}"
        val old=h.begin("fixture",id,1,"a"); val current=h.begin("fixture",id,1,"a")
        h.updateSession(current,"fixture",id,1,"a",10000,100000,false)
        h.updateSession(old,"fixture",id,1,"a",90000,100000,false)
        assertEquals(10000L,PlaybackHistory(c).load("fixture",id,1,"a")!!.positionMs)
        h.updateSession(current,"fixture",id,1,"a",100000,100000,true)
        h.updateSession(current,"fixture",id,1,"a",50000,100000,false)
        assertNull(h.load("fixture",id,1,"a"));assertTrue(PlaybackHistory(c).isWatched("fixture",id,1,"a"))
        assertNull(h.load("fixture",id,2,"a"))
    }
    @Test fun trackPreferencesRetainLanguageAndOffWithoutSourceIds() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=c.getSharedPreferences("herrin-track-preferences",0);val original=prefs.all
        try {
            prefs.edit().clear().commit()
            PlaybackTrackPreferences(c).save(Track(C.TRACK_TYPE_AUDIO,"private-label","private-format").apply {
                language="en";isSelected=true;ordinal=99
            })
            PlaybackTrackPreferences(c).save(Track(C.TRACK_TYPE_TEXT,"","").apply { isSelected=false })
            val loaded=PlaybackTrackPreferences(c).load()
            assertEquals("en",loaded.first { it.type==C.TRACK_TYPE_AUDIO }.language)
            assertFalse(loaded.first { it.type==C.TRACK_TYPE_TEXT }.isSelected)
            assertFalse(prefs.all.toString().contains("private"))
        } finally {
            val e=prefs.edit().clear(); original.forEach { (k,v) -> when(v){is String->e.putString(k,v);is Boolean->e.putBoolean(k,v)} };e.commit()
        }
    }
}
