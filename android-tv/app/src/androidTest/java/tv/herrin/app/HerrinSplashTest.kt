package tv.herrin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic clock assertions on the same Splash composable used by MainActivity. */
class HerrinSplashTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("mkdir -p /data/local/tmp/herrin-test-evidence", "screencap -p /data/local/tmp/herrin-test-evidence/$name.png")) {
            i.uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
    }
    @Test fun completeHerrinWordmarkStaysWholeUntilFastFade() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Splash(false) { completed = true } }
            }
        }
        compose.onNodeWithContentDescription("HERRIN").assertIsDisplayed()
        compose.onNodeWithTag("splash-wordmark").assertExists()
        // There is never a single-letter H-only screen.
        compose.onNodeWithText("H").assertDoesNotExist()
        screenshot("splash-HERRIN")
        compose.mainClock.advanceTimeBy(2100)
        compose.runOnIdle { assertFalse("Full reveal alone must not dismiss the wordmark", completed) }
        compose.mainClock.advanceTimeBy(2400)
        compose.runOnIdle { assertTrue("Normal exit requires a complete breath", completed) }
        compose.onNodeWithContentDescription("HERRIN").assertExists()
    }
    @Test fun homePreparationRunsOnceAndOnlyAfterCompleteWordmarkReveal() {
        compose.mainClock.autoAdvance = false
        var prepared = 0
        var ready = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HerrinWordmarkSplash(false, done = { ready++ }, onVisible = { prepared++ })
            }
        }
        compose.mainClock.advanceTimeBy(850)
        compose.runOnIdle {
            assertEquals("Do not mount the heavy home halfway through the logo animation", 0, prepared)
            assertEquals(0, ready)
        }
        compose.mainClock.advanceTimeBy(1_300)
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(0, ready)
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.runOnIdle { assertEquals(1,prepared);assertEquals(1,ready) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals("Repeated frame ticks must not restart heavy home creation", 1, prepared)
            assertEquals(1, ready)
        }
    }

    @Test fun skipCompletesImmediately() {
        compose.mainClock.autoAdvance = false
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(false) { completed = true } } } }
        compose.onNodeWithText("跳过 · 确定").assertDoesNotExist()
        compose.onNodeWithTag("splash").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun reducedMotionCompletesWithoutWaitingForAnimation() {
        var completed = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Splash(true) { completed = true } } } }
        compose.runOnIdle { assertTrue(completed) }
    }
    @Test fun exitCurtainOverlapsHomeBeforeCompleting() {
        compose.mainClock.autoAdvance = false
        var exiting by mutableStateOf(false)
        var finished = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF102040))) {
                    Text("首页内容", Modifier.testTag("home-underlay"))
                    SplashExitCurtain(false, exiting, onReady = { exiting = true },
                        onFinished = { finished = true })
                }
            }
        }
        val initialLeft = compose.onNodeWithTag("splash-curtain")
            .fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { exiting = true }
        // Let the LaunchedEffect start its Animatable before advancing the
        // deterministic frame clock. Otherwise the first frame can consume
        // the entire advance just to schedule the slide coroutine.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("home-underlay").assertExists()
        compose.onNodeWithTag("splash-curtain").assertExists()
        val shiftedLeft = compose.onNodeWithTag("splash-curtain")
            .fetchSemanticsNode().boundsInRoot.left
        assertEquals("Wordmark remains fixed during fade", initialLeft, shiftedLeft, 0.1f)
        val fading=compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Fade reveals the already painted home, not an empty black frame",android.graphics.Color.blue(fading.getPixel(20,20))>0)
        compose.runOnIdle { assertFalse(finished) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertTrue(finished) }
    }
    @Test fun reducedMotionStillExitsWhenHomeBecomesReadyLater() {
        var ready by mutableStateOf(false)
        var released = false
        compose.setContent {
            MaterialTheme { SplashExitCurtain(true, false, onReady = { released = true },
                onFinished = {}, homeReady = ready) }
        }
        compose.runOnIdle { assertFalse(released); ready = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(released) }
    }
    @Test fun centerWaveStartsInvisibleAndAllSixLettersShareARealWhiteHaloBreath() {
        compose.mainClock.autoAdvance=false
        var time by androidx.compose.runtime.mutableFloatStateOf(0f)
        val renderer=WordmarkRenderer()
        compose.setContent {
            androidx.compose.foundation.Canvas(Modifier.size(960.dp,240.dp).background(Color.Black).testTag("wordmark-probe")) {
                renderer.draw(drawContext.canvas.nativeCanvas,size.width,size.height,time)
            }
        }
        val frames=linkedMapOf<Int,Bitmap>()
        for(t in listOf(0,450,900,1800,2400,3000,4200)) {
            compose.runOnIdle { time=t.toFloat() }
            compose.mainClock.advanceTimeByFrame()
            val bitmap=compose.onNodeWithTag("wordmark-probe").captureToImage().asAndroidBitmap()
            frames[t]=bitmap
            val out=java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
            val encoded=android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)
            val inst=InstrumentationRegistry.getInstrumentation()
            for(command in listOf("mkdir -p /data/local/tmp/herrin-test-evidence",
                "printf %s '$encoded' | base64 -d > /data/local/tmp/herrin-test-evidence/wordmark-$t.png"))
                inst.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}
        }
        fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}
        fun red(color:Int)=android.graphics.Color.red(color)
        val zero=pixels(frames.getValue(0));val full=pixels(frames.getValue(1800))
        val partial=pixels(frames.getValue(450));val dim=pixels(frames.getValue(3000));val end=pixels(frames.getValue(4200))
        assertTrue("No dim wordmark prepaint",zero.all{red(it)==0})
        val width=frames.getValue(1800).width
        fun mean(colors:IntArray,from:Float,to:Float):Double {
            val indices=full.indices.filter{red(full[it])>230 && (it%width)*960f/width in from..to}
            assertTrue(indices.isNotEmpty())
            return indices.map{red(colors[it])}.average()
        }
        assertTrue("Center appears before either outer letter",mean(partial,379f,659f)>mean(partial,37f,163f)+10)
        assertEquals(0.0,mean(partial,797f,923f),1.0)
        for((from,to) in listOf(37f to 163f,228f to 324f,379f to 496f,543f to 659f,706f to 732f,797f to 923f)) {
            assertTrue("All letters dim together",mean(dim,from,to)/mean(full,from,to) in 0.65..0.8)
            assertEquals("All letters return to white without moving",mean(full,from,to),mean(end,from,to),1.0)
        }
        assertTrue("White light must extend outside the strokes",full.indices.count{red(full[it])==0 && red(dim[it])>10}>300)
        android.util.Log.i("HerrinWordmarkEvidence","frames=7 letters=6 initial_visible_pixels=0 whole_breath_ms=2400 physical_tcl=0")
    }
    @Test fun normalMotionKeepsBreathingUntilHomeIsReadyAfterTheMinimumCycle() {
        compose.mainClock.autoAdvance=false
        var homeReady by mutableStateOf(false)
        var exiting by mutableStateOf(false)
        var readyCount=0;var finished=false
        compose.setContent { Box(Modifier.fillMaxSize()) {
            Text("首页内容",Modifier.testTag("waiting-home"))
            SplashExitCurtain(false,exiting,onReady={readyCount++;exiting=true},onFinished={finished=true},homeReady=homeReady)
        } }
        compose.mainClock.advanceTimeBy(5500)
        compose.runOnIdle{assertEquals(0,readyCount);assertFalse(finished)}
        compose.onNodeWithTag("splash-wordmark").assertExists()
        compose.mainClock.advanceTimeBy(2400)
        compose.runOnIdle{assertEquals(0,readyCount);homeReady=true}
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle{assertEquals(1,readyCount);assertTrue(finished)}
        compose.onNodeWithTag("waiting-home").assertExists()
    }

}
