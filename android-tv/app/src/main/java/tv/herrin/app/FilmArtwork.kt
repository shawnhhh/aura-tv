package tv.herrin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Copyright-safe fallback artwork is bundled, even when external provider images are unavailable. */
@Composable
fun FilmArtwork(film: Film, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    var poster by remember(film.artworkUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(film.artworkUrl) {
        poster = withContext(Dispatchers.IO) {
            runCatching { film.artworkUrl?.let {
                AuthorizedPosterCache(context).load(it,
                    allowHttp = film.id.startsWith("tvbox-") || film.spiderVodId != null)
            } }.getOrNull()
        }
    }
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = tween(280), label = "poster-fade-in")
    Image(painterResource(film.artwork), film.title, modifier, contentScale = contentScale)
    if (poster != null) {
        Image(poster!!.asImageBitmap(), film.title, modifier.alpha(posterAlpha),
            contentScale = contentScale)
    }
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
