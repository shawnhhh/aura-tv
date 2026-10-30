package tv.herrin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/** Copyright-safe fallback artwork is bundled, even when external provider images are unavailable. */
private fun Film.isOriginalDemo() = id in setOf("orbit", "tide", "dawn", "night", "return", "blue") &&
    artworkUrl == null && backdropUrl == null

@Composable
fun FilmArtwork(film: Film, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val poster = rememberAuthorizedPoster(film, hero = true)
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = tween(280), label = "poster-fade-in")
    val hasExternalImage = film.artworkUrl != null || film.backdropUrl != null ||
        (!film.isOriginalDemo() && LocalContext.current
            .getSharedPreferences("herrin", Context.MODE_PRIVATE)
            .getString("tmdb_read_token", "").orEmpty().isNotBlank())
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        if (!hasExternalImage) Image(painterResource(film.artwork), film.title,
            Modifier.fillMaxSize(), contentScale = contentScale)
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(
            listOf(Color(0xFF1D2B40), Color(0xFF060910), Color(0xFF111827)))))
        if (poster != null) {
            val bitmap = poster.asImageBitmap()
            if (poster.width >= 960 && poster.width.toFloat() / poster.height >= 1.28f) {
                // A real wide backdrop can safely fill the television viewport.
                Image(bitmap, film.title, Modifier.fillMaxSize().alpha(posterAlpha),
                    contentScale = ContentScale.Crop)
            } else {
                // Do not magnify a 300px portrait into a 4K wallpaper. Give it a
                // blurred cinematic background and a sharp, aspect-preserving foreground.
                Image(bitmap, null, Modifier.fillMaxSize().blur(32.dp).alpha(0.38f * posterAlpha),
                    contentScale = ContentScale.Crop)
                Image(bitmap, film.title, Modifier.fillMaxWidth(0.58f).fillMaxHeight(0.90f)
                    .alpha(0.72f * posterAlpha), contentScale = ContentScale.Fit)
            }
        }
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
    val remoteArtwork = !film.artworkUrl.isNullOrBlank() ||
        (!film.isOriginalDemo() && LocalContext.current
            .getSharedPreferences("herrin", Context.MODE_PRIVATE)
            .getString("tmdb_read_token", "").orEmpty().isNotBlank())
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

/** Optional TMDB person portrait. Missing photos use an original neutral fallback. */
@Composable
fun PersonPortrait(person: FilmPerson, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = person.portraitUrl?.takeIf { it.startsWith("https://image.tmdb.org/t/p/w185/") &&
        TmdbPortraits.url(it.substringAfter("/w185")) == it }
    var portrait by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        portrait = url?.let { candidate ->
            withContext(Dispatchers.IO) {
                runCatching { AuthorizedPosterCache(context).load(candidate) }.getOrNull()
            }
        }
    }
    Box(modifier.clip(CircleShape).background(Color(0xFF283649)),
        contentAlignment = Alignment.Center) {
        if (portrait != null) Image(portrait!!.asImageBitmap(), person.name,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(person.name.take(1).ifBlank { "人" }, color = Color(0xFFD3DEEC),
            fontSize = 29.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun rememberAuthorizedPoster(film: Film, hero: Boolean = false): Bitmap? {
    val context = LocalContext.current
    // TMDB requires a user-provided Read Access Token, never a bundled production credential.
    // Reading SharedPreferences on composition lets the settings screen switch enrichment on/off.
    val tmdbToken = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
        .getString("tmdb_read_token", "").orEmpty()
    val url = if (hero) film.backdropUrl ?: film.artworkUrl else film.artworkUrl
    var poster by remember(url, film.id, film.year, film.title, tmdbToken, hero) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, film.id, film.year, film.title, tmdbToken, hero) {
        coroutineScope {
            // Render the source poster as soon as it is available, even if the user's TMDB
            // token is rejected, offline, rate-limited, or an 80-title shelf is still loading.
            // Both paths run concurrently, and the original image cannot overwrite an upgrade.
            var upgraded = false
            launch {
                val fallback = withContext(Dispatchers.IO) {
                    runCatching {
                        val sourceUrls = if (hero) listOfNotNull(film.backdropUrl, film.artworkUrl)
                            else listOfNotNull(film.artworkUrl)
                        val cache = AuthorizedPosterCache(context)
                        sourceUrls.distinct().firstNotNullOfOrNull {
                            cache.load(it,
                            allowHttp = film.id.startsWith("tvbox-") || film.spiderVodId != null,
                            hero = hero)
                        }
                    }.getOrNull()
                }
                if (!upgraded && fallback != null) poster = fallback
            }
            if (!film.isOriginalDemo() && tmdbToken.isNotBlank() && film.metadataSource != "TMDB") launch {
                val enrichedBitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val enriched = ArtworkMetadataResolver(File(context.cacheDir, "tmdb-artwork"))
                            .lookup(film, tmdbToken)
                        val urls = if (hero) listOfNotNull(enriched?.backdropUrl, enriched?.posterUrl)
                            else listOfNotNull(enriched?.posterUrl)
                        val cache = AuthorizedPosterCache(context)
                        urls.firstNotNullOfOrNull { candidate -> cache.load(candidate, hero = hero) }
                    }.getOrNull()
                }
                if (enrichedBitmap != null) {
                    upgraded = true
                    poster = enrichedBitmap
                }
            }
        }
    }
    return poster
}

/** Small bounded private disk cache; no SDK tracker, cookies, or downloaded executable content. */
class AuthorizedPosterCache(
    private val context: Context,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    companion object {
        // Bound decoded bitmaps (not just compressed JPEG bytes) across LazyRow
        // disposal/recreation. Memory is capped, adapted to device heap size.
        private val memory = object : LruCache<String, MemoryEntry>(
            (Runtime.getRuntime().maxMemory() / 12L)
                .coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()) {
            override fun sizeOf(key: String, entry: MemoryEntry): Int =
                entry.bitmap.byteCount.coerceAtLeast(1)
        }
        // Stable stripes prevent repeat downloads for the same image; do not
        // serialize every poster behind one global network lock.
        private val imageLocks = Array(64) { Any() }
        private val networkSlots = Semaphore(4, true)
    }

    private data class MemoryEntry(
        val bitmap: Bitmap, val updated: Long, val diskSize: Long
    )

    fun load(raw: String, allowHttp: Boolean = false, hero: Boolean = false): Bitmap? {
        val url = (if (allowHttp) TvBoxUrl.normalize(raw, true) else AuthorizedUrls.https(raw))
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "authorized-posters"), "$digest.img")
        val memoryKey = file.absolutePath + if (hero) ":hero" else ":tile"
        val stripe = imageLocks[(digest.hashCode() and Int.MAX_VALUE) % imageLocks.size]

        // A file that was cleared, replaced or corrupted must not keep its
        // previous decoded bitmap alive in the LRU cache.
        fun memoryHit(): Bitmap? {
            val entry = memory.get(memoryKey) ?: return null
            if (file.isFile && file.length() == entry.diskSize &&
                file.lastModified() == entry.updated && !entry.bitmap.isRecycled) {
                return entry.bitmap
            }
            memory.remove(memoryKey)
            return null
        }
        fun rememberDecoded(image: Bitmap): Bitmap {
            if (file.isFile) memory.put(memoryKey, MemoryEntry(
                image, file.lastModified(), file.length()))
            return image
        }

        memoryHit()?.let { return it }
        synchronized(stripe) {
            memoryHit()?.let { return it }
            val existing = file.takeIf { it.isFile && it.length() in 1..8_000_000 }
            if (existing != null) {
                val decoded = decode(existing.readBytes(), hero)
                if (decoded != null) return rememberDecoded(decoded)
                existing.delete()
                memory.remove(memoryKey)
            }

            // Limit image network/decoder contention on resource-constrained
            // Android TVs. Do not expose cookies or trigger Spider scripts.
            networkSlots.acquire()
            try {
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
                            if (data.size() + count > 8_000_000) return null
                            data.write(chunk, 0, count)
                        }
                    }
                    val bytes = data.toByteArray()
                    val bitmap = decode(bytes, hero) ?: return null
                    file.parentFile?.mkdirs()
                    val cached = file.parentFile?.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
                    if (cached.size > 150 || cached.sumOf { it.length() } > 100_000_000L) {
                        cached.take(25).forEach { it.delete() }
                    }
                    runCatching { file.writeBytes(bytes) }
                    return rememberDecoded(bitmap)
                } finally {
                    connection.disconnect()
                }
            } finally {
                networkSlots.release()
            }
        }
    }

    private fun decode(bytes: ByteArray, hero: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1 ||
            bounds.outWidth > 10000 || bounds.outHeight > 10000) return null
        var sample = 1
        val maxWidth = if (hero) 2560 else 640
        val maxHeight = if (hero) 1440 else 960
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample
                inPreferredConfig = if (hero) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565 }
        )
    }
}
