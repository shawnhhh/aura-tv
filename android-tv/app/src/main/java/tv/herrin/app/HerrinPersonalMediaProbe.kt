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
 * Bounded, header-only reachability check for a user-authorized WebDAV video.
 * Sends a one-byte HTTP Range GET; NEVER consumes the actual video body.
 * Redirects are disabled to prevent personal NAS/Cloud credentials crossing hosts.
 *
 * Response 200 is accepted when the provider ignores Range but responds with
 * media/opaque content. HTML/XML/JSON login/error pages are never playable.
 */
internal class HerrinPersonalMediaProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .callTimeout(2_000, TimeUnit.MILLISECONDS)
        .build(),
    private val strictHttpFailure:Boolean=false
) {
    suspend fun canRead(media: PlaybackMedia): Boolean = withContext(Dispatchers.IO) {
        val url = runCatching { URI(media.uri) }.getOrNull() ?: return@withContext false
        if (url.host == null || url.rawUserInfo != null || url.fragment != null ||
            url.scheme?.lowercase() !in setOf("http", "https")) return@withContext false
        // Only the pre-authorized PlaybackMedia headers are forwarded. Do not
        // ever follow 3xx responses; cloud login redirects are not video files.
        val request = try {
            Request.Builder().url(media.uri).get().apply {
                for ((name, value) in media.headers) header(name, value)
                header("Range", "bytes=0-0")
                header("Accept-Encoding", "identity")
            }.build()
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        try {
            readSourceResponse(client,request) { response ->
                    if (response.code !in setOf(200, 206)) {
                        if(strictHttpFailure)throw SourceHttpFailure(response.code,SourceHealthIndex.retryAfter(response.header("Retry-After"),System.currentTimeMillis()))
                        return@readSourceResponse false
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim().lowercase()
                    if (contentType in setOf("text/html", "application/xhtml+xml",
                            "application/json", "text/json", "application/xml",
                            "text/xml", "text/plain")) return@readSourceResponse false
                    if (response.code == 206) {
                        // Range claims must include the requested first byte,
                        // not a malformed/unrelated Partial Content response.
                        val range = response.header("Content-Range").orEmpty()
                        if (!range.startsWith("bytes 0-0/")) return@readSourceResponse false
                    }
                    response.header("Content-Length")?.toLongOrNull()
                        ?.let { if (it <= 0L) return@readSourceResponse false }
                    true
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if(strictHttpFailure &&error is SourceHttpFailure)throw error
            false
        }
    }
}
