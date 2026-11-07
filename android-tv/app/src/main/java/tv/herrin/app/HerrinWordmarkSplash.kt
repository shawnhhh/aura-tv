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
import kotlin.math.cos

internal object WordmarkMotion {
    const val REVEAL_MS = 1800f
    fun brightness(elapsedMs: Float): Float = if (elapsedMs < REVEAL_MS) 1f else
        (0.85 + 0.15 * cos((elapsedMs - REVEAL_MS) / 2400.0 * 2 * Math.PI)).toFloat()
    fun reveal(elapsedMs: Float): Float = (elapsedMs / REVEAL_MS).coerceIn(0f, 1f)
}

/** VSync clock. Draw state stays in this small layer, never recomposes the home per frame. */
@Composable
internal fun HerrinWordmarkSplash(reduced: Boolean, done: () -> Unit, onVisible: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    val onDone by rememberUpdatedState(done)
    val visible by rememberUpdatedState(onVisible)
    val elapsed = remember { mutableFloatStateOf(0f) }
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
        Image(painterResource(R.drawable.launch_wordmark), "HERRIN",
            Modifier.width(420.dp).height(84.dp).testTag("splash-wordmark")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val time = if (reduced) WordmarkMotion.REVEAL_MS else elapsed.floatValue
                    val progress = WordmarkMotion.reveal(time)
                    if (progress < 1f) {
                        // Seven stops: no 33-element map or draw-time Compose writes.
                        val edge = progress * 1.35f
                        val outside = ((1f - edge) / 2f).coerceIn(0f, 0.5f)
                        val inside = ((1f - edge + 0.35f) / 2f).coerceIn(0f, 0.5f)
                        val center = Color.White.copy(alpha = (edge / 0.35f).coerceIn(0f, 1f))
                        val transparent = Color.Transparent
                        drawRect(Brush.horizontalGradient(
                            0f to transparent, outside to transparent,
                            inside to center, 0.5f to center,
                            (1f - inside) to center, (1f - outside) to transparent,
                            1f to transparent
                        ), blendMode = BlendMode.DstIn)
                    } else {
                        drawRect(Color.White.copy(alpha = WordmarkMotion.brightness(time)),
                            blendMode = BlendMode.DstIn)
                    }
                })
    }
}
