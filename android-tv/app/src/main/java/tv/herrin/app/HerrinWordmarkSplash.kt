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
    const val REVEAL_MS = 1800f
    fun brightness(elapsedMs: Float): Float = if (elapsedMs < REVEAL_MS) 1f else
        (0.85 + 0.15 * cos((elapsedMs - REVEAL_MS) / 2400.0 * 2 * Math.PI)).toFloat()
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
    LaunchedEffect(reduced,skipped) {
        runCatching { focus.requestFocus() }
        if (reduced ||skipped) {
            elapsed.floatValue=WordmarkMotion.REVEAL_MS
            StartupTiming.wordmarkVisible()
            StartupTiming.wordmarkComplete()
            visible()
            onDone()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var reportedVisible = false
        var reportedReady = false
        while (true) {
            val now = withFrameNanos { it }
            elapsed.floatValue = (now - start) / 1_000_000f
            if (!reportedVisible && elapsed.floatValue >= WordmarkMotion.REVEAL_MS * 0.08f) {
                reportedVisible = true
                StartupTiming.wordmarkVisible()
            }
            if (!reportedReady && elapsed.floatValue >= WordmarkMotion.REVEAL_MS) {
                reportedReady = true
                StartupTiming.wordmarkComplete()
                // No home UI mutation from the draw phase or before full reveal.
                visible()
                onDone()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("splash")
        .focusRequester(focus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.Enter, Key.DirectionCenter)) {
                skipped=true; true
            } else false
        }.focusable(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.width(widthDp.dp).height((widthDp*.2f).dp)
            .testTag("splash-wordmark").semantics { contentDescription="HERRIN" }) {
            renderer.draw(drawContext.canvas.nativeCanvas,size.width,size.height,
                if(reduced ||skipped)WordmarkMotion.REVEAL_MS else elapsed.floatValue)
            StartupTiming.wordmarkVisible()
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
