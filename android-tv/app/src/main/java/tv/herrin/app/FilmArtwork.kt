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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Copyright-safe fallback artwork is bundled, even when external provider images are unavailable. */
private fun Film.isOriginalDemo() = id in setOf("orbit", "tide", "dawn", "night", "return", "blue") &&
    artworkUrl == null && backdropUrl == null

@Composable
fun FilmArtwork(film: Film, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val poster = rememberAuthorizedPoster(film, hero = true)
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = tween(280), label = "poster-fade-in")
    // External titles without an image show a neutral backdrop, not a
    // misleading demo movie photo. No preferences I/O during every frame.
    val hasExternalImage = !film.isOriginalDemo() ||
        film.artworkUrl != null || film.backdropUrl != null
    val bundled=if(!hasExternalImage)rememberBundledArtwork(film.artwork) else null
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        if (bundled!=null) Image(bundled.asImageBitmap(), film.title,
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
                // A 32dp full-screen RenderEffect blur forces an expensive
                // offscreen GPU pass (especially at 4K). Dim the same source
                // instead: retain image-specific ambience without blocking
                // remote-focus feedback or stretching the portrait foreground.
                Image(bitmap, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alpha = 0.16f * posterAlpha)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    0.0f to Color(0xE9080C13), 0.7f to Color(0x4D080C13),
                    1.0f to Color(0x50080C13))))
                Image(bitmap, film.title, Modifier.fillMaxWidth(0.58f).fillMaxHeight(0.90f),
                    contentScale = ContentScale.Fit, alpha = 0.72f * posterAlpha)
            }
        }
    }
}

/**
 * All small movie-card artwork viewports are filled edge-to-edge using Crop.
 * Only the image is cropped; the separate opaque title/year bar stays unchanged.
 * Remote tiles never flash unrelated demo artwork while loading.
 */
@Composable
fun PosterArtwork(film: Film, modifier: Modifier, reduced: Boolean = false) {
    val poster = rememberAuthorizedPoster(film)
    // Constant-time presentation identity. Avoid SharedPreferences lookups
    // during focus/scroll recompositions across dozens of poster cards.
    val remoteArtwork = !film.isOriginalDemo() || !film.artworkUrl.isNullOrBlank()
    val bundled=if(!remoteArtwork)rememberBundledArtwork(film.artwork) else null
    val posterAlpha by animateFloatAsState(if (poster == null) 0f else 1f,
        animationSpec = if (reduced) snap() else tween(120), label = "card-poster-fade")
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
            // Every small-card artwork viewport is edge-to-edge, including
            // bundled offline demo images. No fit/margin/dimmed letterbox.
            if(bundled!=null)Image(bundled.asImageBitmap(), film.title,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (poster != null) {
            // Fill the entire 217 x 116 dp card; title and release date
            // now overlay its bottom gradient in Poster(), with no opaque footer.
            // Crop from the centre rather than distorting image proportions.
            Image(poster.asImageBitmap(), film.title,
                Modifier.fillMaxSize().alpha(posterAlpha)
                    .testTag("poster-remote-ready"),
                contentScale = ContentScale.Crop)
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
    val enabled=LocalArtworkReady.current
    val url = ArtworkRequestPolicy.url(film, hero)
    // TMDB matching is managed centrally by MainActivity's bounded catalogue
    // enrichment worker. Do NOT start another TMDB request per visible card:
    // that duplicated network, JSON parsing and image downloads during D-pad.
    // The resulting enriched Film updates the URL and triggers this effect.
    var poster by remember(film.id, hero) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, film.id, hero, enabled) {
        if(!enabled)return@LaunchedEffect
        // Rapid cursor movement should not fetch high-res hero pictures for
        // titles that were only focused for a few milliseconds.
        if (hero) kotlinx.coroutines.yield()
        val requestJob = currentCoroutineContext()[Job]
        val cache = AuthorizedPosterCache(context)
        val target = url ?: return@LaunchedEffect
        val allowHttp = film.id.startsWith("tvbox-") || film.spiderVodId != null
        val cancelled = { requestJob?.isActive == false }
        val cached = withContext(Dispatchers.IO) { cache.load(target, allowHttp, hero, cancelled, allowNetwork = false) }
        if (cached != null) { poster = cached; return@LaunchedEffect }
        if (hero && target.startsWith("https://image.tmdb.org/t/p/original/")) {
            val preview = withContext(Dispatchers.IO) { cache.load(target.replace("/original/", "/w1280/"), hero = true, cancelled = cancelled) }
            if (preview != null) poster = preview
        }
        val upgraded = withContext(Dispatchers.IO) { cache.load(target, allowHttp, hero, cancelled) }
        if (upgraded != null) poster = upgraded

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
        private val active = java.util.concurrent.atomic.AtomicInteger()
        internal val activeTasks: Int get() = active.get()
        internal val memoryBytes: Int get() = memory.size()
        fun trimDecodedMemory() { memory.evictAll() } // Never recycle a bitmap still held by Compose.
        private val memory = object : LruCache<String, MemoryEntry>(
            (Runtime.getRuntime().maxMemory() / 12L)
                .coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()) {
            override fun sizeOf(key: String, entry: MemoryEntry): Int =
                entry.bitmap.byteCount.coerceAtLeast(1)
        }
        // Stable stripes prevent repeat downloads for the same image; do not
        // serialize every poster behind one global network lock.
        private val imageLocks = PosterStripeGate()
        private val networkSlots = PosterRequestBudget()
        private val decodeSlot = java.util.concurrent.Semaphore(1, true)
    }

    private data class MemoryEntry(
        val bitmap: Bitmap, val updated: Long, val diskSize: Long
    )

    fun load(raw: String, allowHttp: Boolean = false, hero: Boolean = false,
             cancelled: () -> Boolean = { false }, allowNetwork: Boolean = true): Bitmap? = try {
        active.incrementAndGet()
        try { loadChecked(raw, allowHttp, hero, cancelled, allowNetwork) } finally { active.decrementAndGet() }
    } catch (_: java.io.IOException) { null }
      catch (_: SecurityException) { null }
      catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
      catch (_: OutOfMemoryError) {
          // One failed optional image must not kill browsing; no immediate retry storm or quality downgrade.
          memory.evictAll()
          CrashJournal.stage("IMAGE_OOM")
          null
      }

    private fun loadChecked(raw: String, allowHttp: Boolean = false, hero: Boolean = false,
             cancelled: () -> Boolean = { false }, allowNetwork: Boolean = true): Bitmap? {
        if (cancelled()) return null
        val url = (if (allowHttp) TvBoxUrl.normalize(raw, true) else AuthorizedUrls.https(raw))
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "authorized-posters"), "$digest.img")
        val memoryKey = file.absolutePath + if (hero)
            ":hero:${context.resources.displayMetrics.widthPixels.coerceIn(960,3840)}x${context.resources.displayMetrics.heightPixels.coerceIn(540,2160)}" else ":tile:780x1170"
        val stripeKey = digest.hashCode()

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
        if (!imageLocks.acquire(stripeKey, cancelled)) return null
        try {
            if (cancelled()) return null
            memoryHit()?.let { return it }
            val existing = file.takeIf { it.isFile && it.length() in 1..8_000_000 }
            if (existing != null) {
                val decoded = decode(existing.readBytes(), hero, cancelled)
                if (decoded != null) return rememberDecoded(decoded)
                existing.delete()
                memory.remove(memoryKey)
            }

            // Limit image network/decoder contention on resource-constrained
            // Android TVs. Do not expose cookies or trigger Spider scripts.
            if (!allowNetwork) return null
            if (!networkSlots.acquire(hero = hero, cancelled = cancelled)) return null
            try {
                if (cancelled()) return null
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
                            if (cancelled()) return null
                            val count = stream.read(chunk)
                            if (count < 0) break
                            if (data.size() + count > 8_000_000) return null
                            data.write(chunk, 0, count)
                        }
                    }
                    val bytes = data.toByteArray()
                    if (cancelled()) return null
                    val bitmap = decode(bytes, hero, cancelled) ?: return null
                    if (cancelled()) {
                        bitmap.recycle()
                        return null
                    }
                    // Directory enumeration, sorting and disk-usage walks are
                    // expensive when 60+ posters arrive on a cold TV home.
                    // Store first, then prune at most once per 16 image writes.
                    val folder = file.parentFile
                    if (folder != null && (folder.isDirectory || folder.mkdirs())) {
                        runCatching {
                            file.writeBytes(bytes)
                            PosterDiskCacheMaintenance.onImageStored(folder, file)
                        }
                    }
                    return rememberDecoded(bitmap)
                } finally {
                    connection.disconnect()
                }
            } finally {
                networkSlots.release(hero = hero)
            }
        } finally {
            imageLocks.release(stripeKey)
        }
    }

    private fun decode(bytes: ByteArray, hero: Boolean, cancelled: () -> Boolean): Bitmap? {
        while (!decodeSlot.tryAcquire(35, java.util.concurrent.TimeUnit.MILLISECONDS)) { if (cancelled()) return null }
        try { if (cancelled()) return null; return decodeBounded(bytes, hero) } finally { decodeSlot.release() }
    }
    private fun decodeBounded(bytes: ByteArray, hero: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1 ||
            bounds.outWidth > 10000 || bounds.outHeight > 10000) return null
        var sample = 1
        val maxWidth = if (hero) context.resources.displayMetrics.widthPixels.coerceIn(960, 3840) else 780
        val maxHeight = if (hero) context.resources.displayMetrics.heightPixels.coerceIn(540, 2160) else 1170
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample
                inPreferredConfig = if (hero) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565 }
        ) ?: return null
        // PNG decoders on some Android TV devices ignore inPreferredConfig.
        // For opaque poster tiles explicitly convert to RGB565 to bound GPU
        // memory, but preserve full-quality ARGB8888 on hero backgrounds.
        if (!hero && decoded.config != Bitmap.Config.RGB_565 && !decoded.hasAlpha()) {
            val compact = decoded.copy(Bitmap.Config.RGB_565, false)
            if (compact != null) {
                decoded.recycle()
                return compact
            }
        }
        return decoded
    }
}
