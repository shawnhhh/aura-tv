package tv.herrin.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual sole decoder; neither root-direction nor focused-button holds may flush it repeatedly. */
class PlayerHoldPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun directionAndButtonHoldPreviewWithoutSeekThenCommitOnceAndResume() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.cacheDir,"hold-original-72.mp4")
        inst.context.assets.open("playback-diagnostics/baseline-72.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val observed = AtomicReference<ExoPlayer>()
        val seeks = AtomicInteger()
        try {
            val media = PlaybackMedia(file.toURI().toString(),displayTitle="Original controlled seek fixture")
            compose.setContent { AuthorizedPlaybackPanel(media,onPlayer={ player ->
                if(observed.getAndSet(player) !== player) player.addListener(object:Player.Listener {
                    override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int) {
                        if(reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                })
            },onBack={}) }
            compose.waitUntil(15000) {
                var frames=0
                compose.runOnIdle { frames=observed.get()?.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                frames>3
            }
            for(button in listOf(false,true)) {
                compose.runOnIdle { observed.get().seekTo(10000);observed.get().play() }
                compose.waitUntil(5000) {
                    var ready=false
                    compose.runOnIdle { ready=observed.get().playbackState == Player.STATE_READY && observed.get().isPlaying }
                    ready
                }
                compose.onNodeWithTag("player-root-focus").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                if(button) {
                    inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                    compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("transport-toggle").assertIsFocused() }.isSuccess }
                    compose.onNodeWithTag("transport-forward").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                }
                val before=seeks.get()
                val code=if(button)KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_DPAD_RIGHT
                val down=SystemClock.uptimeMillis()
                inst.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,code,0))
                SystemClock.sleep(100)
                inst.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,1))
                repeat(10) { index ->
                    SystemClock.sleep(40)
                    inst.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,index+2))
                }
                assertEquals("Holding changes only the preview, never repeated decoder seeks",before,seeks.get())
                compose.onNodeWithTag("seek-acceleration").assertDoesNotExist()
                var paused=false
                compose.runOnIdle { paused=!observed.get().playWhenReady }
                assertTrue("Actual playback pauses during the continuous preview",paused)
                inst.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0))
                compose.waitUntil(5000) {
                    var resumed=false
                    compose.runOnIdle { resumed=observed.get().playWhenReady && observed.get().currentPosition in 13000..20000 }
                    seeks.get()==before+1 && resumed
                }
                val after=seeks.get()
                SystemClock.sleep(300)
                assertEquals("No queued seeks may continue after KeyUp",after,seeks.get())
            }
        } finally { file.delete() }
    }
}
