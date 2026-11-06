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
    val firstVisible = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val elapsed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        runCatching { focus.requestFocus() }
        if (reduced) { visible(); onDone(); return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { elapsed.floatValue = (it - start) / 1_000_000f }
            // Home readiness is checked each frame after the complete reveal.
            // A local offline home is always available; never wait for TMDB/network.
            if (elapsed.floatValue >= WordmarkMotion.REVEAL_MS) onDone()
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
                    if (progress >= 0.08f && firstVisible.compareAndSet(false, true)) {
                        StartupTiming.wordmarkVisible()
                        visible()
                    }
                    if (progress >= 1f) StartupTiming.wordmarkComplete()
                    if (progress < 1f) {
                        // Symmetric soft alpha wave, fixed glyph geometry and crisp edges.
                        val edge = progress * 1.35f
                        val stops = (0..32).map { i ->
                            val x = i / 32f
                            val distance = kotlin.math.abs(x - 0.5f) * 2f
                            x to Color.White.copy(alpha = ((edge - distance) / 0.35f).coerceIn(0f, 1f))
                        }.toTypedArray()
                        drawRect(Brush.horizontalGradient(*stops), blendMode = BlendMode.DstIn)
                    } else {
                        drawRect(Color.White.copy(alpha = WordmarkMotion.brightness(time)),
                            blendMode = BlendMode.DstIn)
                    }
                })
    }
}
