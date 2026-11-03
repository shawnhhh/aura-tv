package tv.herrin.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * P0 playback-source gate: verify actual bounded HTTP MEDIA bytes, not merely a
 * Spider playerContent JSON or a CMS episode URL.
 *
 * A 2xx login HTML page, an empty 200 body and an invalid 206 are not video.
 * Localhost is accepted ONLY after the active Spider session proves ownership.
 * No arbitrary localhost HTTP probing, no unsafe redirect with credentials.
 *
 * Does NOT promise the whole movie or decoder works; Media3 frame tests and
 * physical TV acceptance remain separate.
 */
internal class HerrinRemoteMediaProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .callTimeout(2_800, TimeUnit.MILLISECONDS)
        .build(),
    private val authorizeLocal: suspend (String) -> Boolean = {
        SpiderProxyPlayback.diagnose(it) == SpiderProxyPlayback.Health.READY
    }
) {
    enum class Result {
        MEDIA, INVALID_URL, UNAUTHORIZED_LOCAL, HTTP_ERROR, LOGIN_OR_TEXT,
        INVALID_RANGE, EMPTY_BODY, INVALID_MANIFEST, NETWORK_ERROR
    }

    suspend fun canRead(media: PlaybackMedia): Boolean = probe(media) == Result.MEDIA

    suspend fun probe(media: PlaybackMedia): Result {
        var url = media.uri
        var headers = media.headers
        repeat(3) { hop ->
            val target = runCatching { URI(url) }.getOrNull() ?: return Result.INVALID_URL
            if (target.scheme?.lowercase() !in setOf("http", "https") ||
                target.rawUserInfo != null || target.host.isNullOrBlank() ||
                target.fragment != null) return Result.INVALID_URL
            if (isLoopback(target) && !authorizeLocal(url)) return Result.UNAUTHORIZED_LOCAL
            val request = try {
                Request.Builder().url(url).get().apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Range", "bytes=0-1023")
                    header("Accept-Encoding", "identity")
                }.build()
            } catch (_: IllegalArgumentException) {
                return Result.INVALID_URL
            }
            val response = try {
                withContext(Dispatchers.IO) {
                    runInterruptible { client.newCall(request).execute() }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return Result.NETWORK_ERROR
            }
            response.use { reply ->
                if (reply.code in setOf(301, 302, 303, 307, 308)) {
                    if (hop >= 2) return Result.HTTP_ERROR
                    val location = reply.header("Location") ?: return Result.HTTP_ERROR
                    val redirected = runCatching { target.resolve(location) }.getOrNull()
                        ?: return Result.INVALID_URL
                    if (redirected.scheme?.lowercase() !in setOf("http", "https") ||
                        redirected.host.isNullOrBlank() || redirected.rawUserInfo != null ||
                        redirected.fragment != null || isLoopback(redirected))
                        return Result.INVALID_URL
                    // Never propagate a NAS/Spider/CMS cookie, token, or signed
                    // source-specific request header to a new CDN origin.
                    if (!sameOrigin(target, redirected)) {
                        headers = headers.filterKeys { it.lowercase() in
                            setOf("user-agent", "accept", "accept-language") }
                    }
                    url = redirected.toString()
                    return@use
                }
                if (reply.code !in setOf(200, 206)) return Result.HTTP_ERROR
                if (reply.code == 206 &&
                    reply.header("Content-Range").orEmpty().let {
                        !it.startsWith("bytes 0-") || !it.contains('/')
                    }) return Result.INVALID_RANGE
                val contentType = reply.header("Content-Type").orEmpty()
                    .substringBefore(';').trim().lowercase()
                if (contentType in setOf("text/html", "application/xhtml+xml",
                        "application/json", "text/json"))
                    return Result.LOGIN_OR_TEXT
                val buffer = ByteArray(2048)
                val size = try {
                    withContext(Dispatchers.IO) {
                        runInterruptible {
                            reply.body?.byteStream()?.read(buffer) ?: -1
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return Result.NETWORK_ERROR
                }
                if (size <= 0) return Result.EMPTY_BODY
                val beginning = buffer.copyOf(size).toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r','\t')
                if (beginning.startsWith("<!DOCTYPE html", true) ||
                    beginning.startsWith("<html", true) ||
                    beginning.startsWith("{\"error\"", true))
                    return Result.LOGIN_OR_TEXT
                val looksHls = contentType.contains("mpegurl") ||
                    url.substringBefore('?').lowercase().endsWith(".m3u8") ||
                    beginning.startsWith("#EXTM3U")
                if (looksHls) return if (beginning.startsWith("#EXTM3U"))
                    Result.MEDIA else Result.INVALID_MANIFEST
                val looksDash = contentType.contains("dash+xml") ||
                    url.substringBefore('?').lowercase().endsWith(".mpd")
                if (looksDash) return if ("<MPD" in beginning ||
                    ("<?xml" in beginning && "<MPD" in beginning))
                    Result.MEDIA else Result.INVALID_MANIFEST
                if (contentType.startsWith("text/") || contentType.contains("xml"))
                    return Result.LOGIN_OR_TEXT
                return Result.MEDIA
            }
        }
        return Result.HTTP_ERROR
    }

    private fun isLoopback(uri: URI) =
        uri.host?.lowercase() in setOf("127.0.0.1", "::1", "[::1]", "localhost")

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(u: URI) = if (u.port != -1) u.port
        else if (u.scheme.equals("https", true)) 443 else 80
}
