package tv.herrin.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag

/**
 * At most two artwork owners, even when every remote press interrupts the fade.
 * Generic Crossfade retains interrupted targets until its transition settles;
 * keeping those image composables alive also keeps their bitmaps and IO jobs alive.
 */
@Composable
internal fun HeroArtworkTransition(film: Film, reduced: Boolean, modifier: Modifier = Modifier) {
    val committed = remember { arrayOf(film) }
    val outgoing = remember(film.id, reduced) { committed[0].takeUnless { it.id == film.id } }
    SideEffect { committed[0] = film }
    val progress = remember(film.id, reduced) {
        Animatable(if (reduced || outgoing == null) 1f else 0f)
    }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f,
            tween(RemoteMotionPolicy.HERO_CROSSFADE_MS, easing = FastOutSlowInEasing))
    }
    val fading by remember(progress) { derivedStateOf { progress.value < 1f } }
    val layers = listOfNotNull(outgoing.takeIf { !reduced && fading }, film)
    Box(modifier) {
        layers.forEach { layer ->
            // One call site lets a retained current layer become outgoing without restarting its image job.
            key(layer.id) {
                val current = layer.id == film.id
                FilmArtwork(layer, Modifier.fillMaxSize().testTag("hero-art-${layer.id}")
                    .graphicsLayer { alpha = if (current) progress.value else 1f - progress.value })
            }
        }
    }
}
