package tv.herrin.app

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single 16:9 viewport on every TV and source. Never inflates portraits into film stills. */
@Composable
internal fun SeasonEpisodeCard(
    entry: SeasonEpisode,
    metadata: TmdbEpisodeDisplay?,
    position: WatchPosition?,
    selected: Boolean,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val still = metadata?.stillUrl
    var image by remember(still) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(still) {
        image = still?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching { AuthorizedPosterCache(context).load(url) }.getOrNull()
            }
        }
    }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !reduced) 1.048f else 1f,
        if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "season-episode-scale")
    val focusStroke by animateColorAsState(if (focused) Color.White
        else if (selected) Color(0xFF719BCE) else Color.Transparent,
        if (reduced) snap() else tween(175), label = "season-episode-stroke")
    Column(modifier.width(276.dp).graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .border(if (focused) 2.dp else if (selected) 1.dp else 0.dp,
            focusStroke, RoundedCornerShape(14.dp))
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF24354A), Color(0xFF101A29), Color(0xFF0B111B)))),
            contentAlignment = Alignment.Center) {
            if (image != null) Image(image!!.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
            // Textual cue remains visible when TMDB has no verified still.
            if (image == null) Text("HERRIN", fontSize = 14.sp, fontWeight = FontWeight.Light,
                letterSpacing = 2.sp, color = Color(0xFF7791AB))
            Box(Modifier.align(Alignment.Center)
                .size(43.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xB60B1525)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 19.sp)
            }
            if (position != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart)
                    .background(Color(0x884D657A))) {
                    Box(Modifier.fillMaxWidth(position.fraction).fillMaxHeight()
                        .background(Color(0xFFEAF2FC)))
                }
            }
        }
        Text(buildString {
            append("第").append(entry.number).append("集")
            metadata?.officialTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        val durationText = metadata?.runtimeMinutes?.let { "约 $it 分钟" }
        val progressText = position?.let {
            "${formatSeasonClock(it.positionMs)} / ${formatSeasonClock(it.durationMs)}"
        }
        if (durationText != null || progressText != null) {
            Text(listOfNotNull(durationText, progressText).joinToString("  ·  "),
                maxLines = 1, color = Color(0xFFB7C7D9), fontSize = 12.sp)
        }
    }
}

internal fun formatSeasonClock(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
        else "%02d:%02d".format(minutes, seconds % 60)
}
