package tv.herrin.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.cos

internal object WordmarkMotion {
    const val REVEAL_MS = 2250f
    const val BRIGHTEN_MS = 850f
    const val BREATH_MS = 2400f
    const val BREATH_START_MS = REVEAL_MS + BRIGHTEN_MS
    const val REVEAL_BRIGHTNESS = 0.58f
    const val MIN_READY_MS = BREATH_START_MS + 300f
    // V13 revised: the *entire* wordmark first feathers in at a comfortable
    // brightness; only after 2250ms may any letter begin to grow brighter.
    // Smoothly brighten to white, then keep the 2400ms breathing wave alive.
    fun brightness(elapsedMs: Float): Float {
        if (elapsedMs <= REVEAL_MS) return REVEAL_BRIGHTNESS
        if (elapsedMs < BREATH_START_MS) {
            val x = ((elapsedMs - REVEAL_MS) / BRIGHTEN_MS).coerceIn(0f, 1f)
            val eased = x * x * (3f - 2f * x)
            return REVEAL_BRIGHTNESS + (1f - REVEAL_BRIGHTNESS) * eased
        }
        return (0.79 + 0.21 * cos((elapsedMs - BREATH_START_MS) / BREATH_MS * 2 * Math.PI)).toFloat()
    }
    // Confine the subtle, synchronized glow to the six glyph outlines.
    fun glow(elapsedMs: Float): Float =
        if (elapsedMs <= REVEAL_MS) 0f
        else ((brightness(elapsedMs) - REVEAL_BRIGHTNESS) / (1f - REVEAL_BRIGHTNESS)).coerceIn(0f, 1f)
    fun ready(elapsedMs: Float) = elapsedMs >= MIN_READY_MS
    fun reveal(elapsedMs: Float): Float = (elapsedMs / REVEAL_MS).coerceIn(0f, 1f)
}

/** VSync clock. Draw state stays in this small layer, never recomposes the home per frame. */
@Composable
internal fun HerrinWordmarkSplash(reduced: Boolean, done: () -> Unit, onVisible: () -> Unit = {}, widthDp: Float = 420f) {
    val focus = remember { FocusRequester() }
    val onDone by rememberUpdatedState(done)
    val visible by rememberUpdatedState(onVisible)
    val elapsed = remember { mutableFloatStateOf(0f) }
    var skipped by remember { mutableStateOf(false) }
    val renderer=remember{WordmarkRenderer()}
    // Input callbacks must complete in the same dispatch, even when a test or
    // the system pauses the animation clock. Effects and held OK share one gate.
    val ready=remember { booleanArrayOf(false) }
    val prepared=remember { booleanArrayOf(false) }
    fun prepareOnce() { if (!prepared[0]) { prepared[0]=true; visible() } }
    val announced=remember { booleanArrayOf(false) }
    fun revealOnce() {
        if(announced[0])return
        announced[0]=true
        StartupTiming.wordmarkVisible()
        StartupTiming.wordmarkComplete()
        prepareOnce()
    }
    fun completeOnce() {
        if(ready[0])return
        ready[0]=true
        revealOnce()
        onDone()
    }
    LaunchedEffect(reduced,skipped) {
        runCatching { focus.requestFocus() }
        if (reduced ||skipped) {
            elapsed.floatValue=WordmarkMotion.REVEAL_MS
            completeOnce()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var reportedVisible = false
        var reportedReady = false
        while (true) {
            val now = withFrameNanos { it }
            // The previous positive-time frame has now had a draw opportunity.
            // Mounting the large home before that frame can stall first reveal.
            if (reportedVisible) prepareOnce()
            elapsed.floatValue = (now - start) / 1_000_000f
            if (!reportedVisible && elapsed.floatValue > 0f) {
                reportedVisible = true
                StartupTiming.wordmarkVisible()
            }
            if(elapsed.floatValue>=WordmarkMotion.REVEAL_MS)revealOnce()
            if (!reportedReady && WordmarkMotion.ready(elapsed.floatValue)) {
                reportedReady = true
                completeOnce()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("splash")
        .focusRequester(focus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.Enter, Key.DirectionCenter, Key.Back)) {
                skipped=true
                elapsed.floatValue=WordmarkMotion.REVEAL_MS
                completeOnce()
                true
            } else false
        }.focusable(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.width(widthDp.dp).height((widthDp*.25f).dp)
            .testTag("splash-wordmark").semantics { contentDescription="HERRIN" }) {
            renderer.draw(drawContext.canvas.nativeCanvas,size.width,size.height,
                if(reduced ||skipped)WordmarkMotion.REVEAL_MS else elapsed.floatValue)
        }
    }
}


/** Fixed-position wordmark; only opacity changes during exit. */
@Composable
internal fun SplashExitCurtain(reduced: Boolean, exiting: Boolean,
                               onReady: () -> Unit, onFinished: () -> Unit,
                               homeReady: Boolean = true, onVisible: () -> Unit = {}, wordmarkWidthDp: Float = 420f) {
    val opacity = remember { Animatable(1f) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed, homeReady) {
        if (revealed && homeReady) onReady()
    }
    LaunchedEffect(exiting, reduced) {
        if (exiting) {
            if (reduced) opacity.snapTo(0f)
            else opacity.animateTo(0f, tween(LaunchMotion.FADE_MS))
            onFinished()
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }
        .testTag("splash-curtain")) {
        HerrinWordmarkSplash(reduced, done = { revealed = true }, onVisible = onVisible,widthDp=wordmarkWidthDp)
    }
}


@Composable
internal fun Splash(reduced: Boolean, done: () -> Unit) {
    HerrinWordmarkSplash(reduced, done)
}
