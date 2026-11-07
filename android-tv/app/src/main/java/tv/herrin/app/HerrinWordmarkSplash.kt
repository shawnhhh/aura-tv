package tv.herrin.app

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
    val renderer=remember{WordmarkRenderer()}
    LaunchedEffect(reduced) {
        runCatching { focus.requestFocus() }
        if (reduced) {
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
                onDone(); true
            } else false
        }.focusable(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.width(widthDp.dp).height((widthDp*.2f).dp)
            .testTag("splash-wordmark").semantics { contentDescription="HERRIN" }) {
            renderer.draw(drawContext.canvas.nativeCanvas,size.width,size.height,
                if(reduced)WordmarkMotion.REVEAL_MS else elapsed.floatValue)
            StartupTiming.wordmarkVisible()
        }
    }
}
