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
    // A token embedded in an untrusted URL is not user authorization.
    // Global TMDB source lookup explicitly supplies FongMiSessionAuthorization
    // for its current approved Spider site; all other callers fail closed.
    private val authorizeLocal: suspend (String) -> Boolean = { false },
    private val onHttpFailure: (Int, Long?) -> Unit = { _, _ -> },
    private val strictHttpFailure: Boolean = false
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
                    header("Range", "bytes=0-$PROBE_RANGE_END")
                    header("Accept-Encoding", "identity")
                }.build()
            } catch (_: IllegalArgumentException) {
                return Result.INVALID_URL
            }
            val result = try { readSourceResponse(client,request) { reply ->
                if (reply.code in setOf(301, 302, 303, 307, 308)) {
                    if (hop >= 2) return@readSourceResponse Result.HTTP_ERROR
                    val location = reply.header("Location") ?: return@readSourceResponse Result.HTTP_ERROR
                    val redirected = runCatching { target.resolve(location) }.getOrNull()
                        ?: return@readSourceResponse Result.INVALID_URL
                    if (redirected.scheme?.lowercase() !in setOf("http", "https") ||
                        redirected.host.isNullOrBlank() || redirected.rawUserInfo != null ||
                        redirected.fragment != null || isLoopback(redirected))
                        return@readSourceResponse Result.INVALID_URL
                    // Never propagate a NAS/Spider/CMS cookie, token, or signed
                    // source-specific request header to a new CDN origin.
                    if (!sameOrigin(target, redirected)) {
                        headers = headers.filterKeys { it.lowercase() in
                            setOf("user-agent", "accept", "accept-language") }
                    }
                    url = redirected.toString()
                    return@readSourceResponse null
                }
                if (reply.code !in setOf(200, 206)) {
                    val retry=SourceHealthIndex.retryAfter(reply.header("Retry-After"),System.currentTimeMillis())
                    onHttpFailure(reply.code,retry)
                    if(strictHttpFailure)throw SourceHttpFailure(reply.code,retry)
                    return@readSourceResponse Result.HTTP_ERROR
                }
                if (reply.code == 206 &&
                    !validPartialContentRange(reply.header("Content-Range")))
                    return@readSourceResponse Result.INVALID_RANGE
                val contentType = reply.header("Content-Type").orEmpty()
                    .substringBefore(';').trim().lowercase()
                if (contentType in setOf("text/html", "application/xhtml+xml",
                        "application/json", "text/json"))
                    return@readSourceResponse Result.LOGIN_OR_TEXT
                val buffer = ByteArray((PROBE_RANGE_END + 1).toInt())
                val size = try {
                    val stream=reply.body?.byteStream() ?: return@readSourceResponse Result.EMPTY_BODY
                    var received=0
                    while(received<buffer.size) {
                        val count=stream.read(buffer,received,buffer.size-received)
                        if(count<0)break
                        if(count==0)continue
                        received+=count
                    }
                    received
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return@readSourceResponse Result.NETWORK_ERROR
                }
                if (size <= 0) return@readSourceResponse Result.EMPTY_BODY
                val beginning = buffer.copyOf(size).toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r','\t')
                // An expired media URL can return a JSON status/player
                // envelope with a misleading binary/video Content-Type.
                // This is control text, never actual Media3 video bytes.
                val jsonEnvelope = beginning.startsWith("{") &&
                    beginning.drop(1).trimStart().startsWith("\"")
                if (beginning.startsWith("<!DOCTYPE html", true) ||
                    beginning.startsWith("<html", true) || jsonEnvelope)
                    return@readSourceResponse Result.LOGIN_OR_TEXT
                val looksHls = contentType.contains("mpegurl") ||
                    url.substringBefore('?').lowercase().endsWith(".m3u8") ||
                    beginning.startsWith("#EXTM3U")
                if (looksHls) return@readSourceResponse if (beginning.startsWith("#EXTM3U"))
                    Result.MEDIA else Result.INVALID_MANIFEST
                val looksDash = contentType.contains("dash+xml") ||
                    url.substringBefore('?').lowercase().endsWith(".mpd")
                if (looksDash) return@readSourceResponse if ("<MPD" in beginning ||
                    ("<?xml" in beginning && "<MPD" in beginning))
                    Result.MEDIA else Result.INVALID_MANIFEST
                if (contentType.startsWith("text/") || contentType.contains("xml"))
                    return@readSourceResponse Result.LOGIN_OR_TEXT
                Result.MEDIA
            } } catch(cancel:CancellationException){throw cancel} catch(error:Exception){
                if(strictHttpFailure &&error is SourceHttpFailure)throw error
                return Result.NETWORK_ERROR
            }
            if(result!=null)return result
        }
        return Result.HTTP_ERROR
    }

    // Source selection must not mistake a malformed or impossible partial
    // response for a playable video. HTTP permits an unknown total ("*").
    // The probe requests the first byte range, so the response must start at 0.
    private val partialContentRange = Regex("""bytes 0-([0-9]{1,18})/([0-9]{1,18}|[*])""")

    private fun validPartialContentRange(header: String?): Boolean {
        val match = partialContentRange.matchEntire(header.orEmpty().trim())
            ?: return false
        val end = match.groupValues[1].toLongOrNull() ?: return false
        val total = match.groupValues[2]
        return end <= PROBE_RANGE_END &&
            (total == "*" || (total.toLongOrNull()?.let { it > end } == true))
    }

    private fun isLoopback(uri: URI) =
        uri.host?.lowercase() in setOf("127.0.0.1", "::1", "[::1]", "localhost")

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(u: URI) = if (u.port != -1) u.port
        else if (u.scheme.equals("https", true)) 443 else 80

    private companion object {
        const val PROBE_RANGE_END = 1023L
    }
}
