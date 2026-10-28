package tv.herrin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Copyright-safe fallback artwork is bundled, even when external provider images are unavailable. */
@Composable
fun FilmArtwork(film: Film, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val poster = rememberAuthorizedPoster(film)
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = tween(280), label = "poster-fade-in")
    Image(painterResource(film.artwork), film.title, modifier, contentScale = contentScale)
    if (poster != null) {
        Image(poster.asImageBitmap(), film.title, modifier.alpha(posterAlpha),
            contentScale = contentScale)
    }
}

/**
 * All cards have the same viewport. Portrait vod_pic artwork stays fully visible
 * in front of a dim, cropped copy of itself; no stretched or truncated poster.
 * Unlike the full-screen hero, a remote tile never flashes unrelated demo art.
 */
@Composable
fun PosterArtwork(film: Film, modifier: Modifier, reduced: Boolean = false) {
    val poster = rememberAuthorizedPoster(film)
    val remoteArtwork = !film.artworkUrl.isNullOrBlank()
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = if (reduced) snap() else tween(280), label = "card-poster-fade")
    Box(modifier, contentAlignment = Alignment.Center) {
        if (remoteArtwork) {
            // Source images load asynchronously. Never impersonate a real title
            // with one of HERRIN's unrelated original demo-film photographs.
            Box(Modifier.fillMaxSize().testTag("poster-source-placeholder")
                .background(Brush.linearGradient(listOf(Color(0xFF192637), Color(0xFF0A1019)))),
                contentAlignment = Alignment.Center) {
                Text("H", fontSize = 22.sp, fontWeight = FontWeight.Light,
                    color = Color(0x708BA0B8))
            }
        } else {
            Image(painterResource(film.artwork), null, Modifier.fillMaxSize().blur(10.dp),
                contentScale = ContentScale.Crop, alpha = 0.24f)
            Image(painterResource(film.artwork), film.title,
                Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
        }
        if (poster != null) {
            Box(Modifier.fillMaxSize().alpha(posterAlpha).testTag("poster-remote-ready")
                .background(Color(0xFF101821))) {
                val image = poster.asImageBitmap()
                Image(image, null, Modifier.fillMaxSize().blur(12.dp),
                    contentScale = ContentScale.Crop, alpha = 0.34f)
                Box(Modifier.fillMaxSize().background(Color(0xA20C111A)))
                Image(image, film.title, Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun rememberAuthorizedPoster(film: Film): Bitmap? {
    val context = LocalContext.current
    var poster by remember(film.artworkUrl, film.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(film.artworkUrl, film.id) {
        poster = withContext(Dispatchers.IO) {
            runCatching { film.artworkUrl?.let {
                AuthorizedPosterCache(context).load(it,
                    allowHttp = film.id.startsWith("tvbox-") || film.spiderVodId != null)
            } }.getOrNull()
        }
    }
    return poster
}

/** Small bounded private disk cache; no SDK tracker, cookies, or downloaded executable content. */
class AuthorizedPosterCache(
    private val context: Context,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    fun load(raw: String, allowHttp: Boolean = false): Bitmap? {
        // TVBox allows plain HTTP posters. Only explicitly imported TVBox
        // catalogs may take this path; legacy catalog posters remain HTTPS-only.
        val url = (if (allowHttp) TvBoxUrl.normalize(raw, true) else AuthorizedUrls.https(raw))
            ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "authorized-posters")
        val file = File(dir, "$key.img")
        val existing = file.takeIf { it.isFile && it.length() in 1..4_000_000 }
        if (existing != null) {
            val bitmap = decode(existing.readBytes())
            if (bitmap != null) return bitmap
            existing.delete() // A damaged cached image must not permanently suppress network recovery.
        }
        val connection = openConnection(url).apply {
            connectTimeout = 7000
            readTimeout = 8000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
        }
        try {
            if (connection.responseCode != 200) return null
            val type = connection.contentType?.lowercase()?.substringBefore(';') ?: return null
            if (type !in setOf("image/jpeg", "image/png", "image/webp")) return null
            val data = java.io.ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (data.size() + count > 4_000_000) return null
                    data.write(chunk, 0, count)
                }
            }
            val bytes = data.toByteArray()
            val bitmap = decode(bytes) ?: return null
            dir.mkdirs()
            if (dir.listFiles()?.size ?: 0 > 50) {
                dir.listFiles()?.sortedBy { it.lastModified() }?.take(10)?.forEach { it.delete() }
            }
            runCatching { file.writeBytes(bytes) }
            return bitmap
        } finally { connection.disconnect() }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1 ||
            bounds.outWidth > 10000 || bounds.outHeight > 10000) return null
        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        )
    }
}
