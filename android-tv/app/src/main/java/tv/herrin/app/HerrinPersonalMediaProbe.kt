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
    suspend fun canRead(media: PlaybackMedia): Boolean = resolve(media) != null

    /** Resolve only explicit server redirects; never persist the signed CDN URL. */
    suspend fun resolve(media: PlaybackMedia): PlaybackMedia? = withContext(Dispatchers.IO) {
        var selected = media
        repeat(5) { hop ->
            val url = runCatching { URI(selected.uri) }.getOrNull() ?: return@withContext null
            if (url.host == null || url.rawUserInfo != null || url.fragment != null ||
                url.scheme?.lowercase() !in setOf("http", "https")) return@withContext null
            val request = Request.Builder().url(selected.uri).get().apply {
                selected.headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=0-0"); header("Accept-Encoding", "identity")
            }.build()
            var redirected: PlaybackMedia? = null
            val valid = try {
                readSourceResponse(client, request) { response ->
                    if (response.code in setOf(301,302,303,307,308)) {
                        val next = response.header("Location")?.let { runCatching { url.resolve(it) }.getOrNull() }
                            ?: return@readSourceResponse false
                        if (hop == 4 || next.host == null || next.rawUserInfo != null || next.fragment != null ||
                            next.scheme?.lowercase() !in setOf("http", "https") ||
                            (url.scheme == "https" && next.scheme != "https")) return@readSourceResponse false
                        val same = PersonalStoragePolicy.sameOrigin(url, next)
                        if (!same && next.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) return@readSourceResponse false
                        val headers = if (same) selected.headers else selected.headers.filterKeys {
                            it.lowercase() in setOf("user-agent", "accept", "accept-language")
                        }
                        redirected = selected.copy(uri = next.toASCIIString(), headers = headers)
                        return@readSourceResponse false
                    }
                    if (response.code !in setOf(200,206)) {
                        if (strictHttpFailure) throw SourceHttpFailure(response.code,
                            SourceHealthIndex.retryAfter(response.header("Retry-After"), System.currentTimeMillis()))
                        return@readSourceResponse false
                    }
                    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (type in setOf("text/html", "application/xhtml+xml", "application/json", "text/json",
                            "application/xml", "text/xml", "text/plain")) return@readSourceResponse false
                    if (response.code == 206 && !Regex("bytes 0-0/(?:[1-9][0-9]*|[*])")
                            .matches(response.header("Content-Range").orEmpty().trim())) return@readSourceResponse false
                    response.header("Content-Length")?.toLongOrNull()?.let { if (it <= 0) return@readSourceResponse false }
                    true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (strictHttpFailure && error is SourceHttpFailure) throw error
                return@withContext null
            }
            if (valid) return@withContext selected
            selected = redirected ?: return@withContext null
        }
        null
    }
}
