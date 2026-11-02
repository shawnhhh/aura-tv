/*
 * Host-side network stream for FongMi/mpv-android media3iso:// callback.
 * No site credentials are sent to a redirect target: redirects are rejected.
 * See android-tv/LICENSE-MPV-ANDROID-MIT.txt and GPL-3.0 project terms.
 */
package tv.herrin.app

import `is`.xyz.mpv.MPVLib
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A real seekable, per-session MPV stream for progressive HTTP media.
 * MPV's native stream.cpp calls read/seek/size/cancel/close via JNI.
 *
 * HLS, DASH, web-parser endpoints and URL redirects need different handling.
 * This class only supports explicitly authorized progressive media; it cannot
 * bypass website auth, DRM or a network policy.
 */
internal class MpvSecureHttpStream(
    private val uri: String,
    headers: Map<String, String>,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
) : MPVLib.Stream {
    private val requestHeaders = headers.toMap()
    private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelled = false
    private var activeResponse: Response? = null
    private var input: InputStream? = null
    private var offset = 0L
    private var knownSize = -1L

    init {
        val parsed = okhttp3.HttpUrl.parse(uri)
            ?: throw IllegalArgumentException("Expected a valid HTTP(S) media URI")
        require(parsed.scheme == "https" || parsed.scheme == "http") { "Unsupported media URI" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Embedded URL credentials rejected" }
        require(requestHeaders.size <= 32 && requestHeaders.all { (name, value) ->
            name.matches(Regex("[A-Za-z0-9-]{1,128}")) && value.length <= 8192 &&
                '\r' !in value && '\n' !in value
        }) { "Invalid stream request header" }
    }

    @Synchronized private fun openAt(position: Long): InputStream {
        if (cancelled) throw IOException("Stream cancelled")
        val builder = Request.Builder().url(uri)
        requestHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (position > 0L) builder.header("Range", "bytes=$position-")
        val call = client.newCall(builder.build())
        activeCall = call
        val response = try { call.execute() } catch (error: IOException) {
            activeCall = null
            throw error
        }
        if (cancelled) {
            response.close()
            throw IOException("Stream cancelled")
        }
        if (!response.isSuccessful || (position > 0L && response.code != 206)) {
            val status = response.code
            response.close()
            throw IOException("Progressive media response code $status")
        }
        val range = response.header("Content-Range")?.let(contentRange::matchEntire)
        if (response.code == 206) {
            // Failing closed prevents a server returning a wrong segment after seek.
            if (range == null || range.groupValues[1].toLongOrNull() != position) {
                response.close()
                throw IOException("Invalid media Content-Range")
            }
            knownSize = range.groupValues[3].toLongOrNull() ?: -1L
        } else {
            knownSize = response.body?.contentLength()?.takeIf { it >= 0L } ?: -1L
        }
        val stream = response.body?.byteStream() ?: run {
            response.close()
            throw IOException("Missing HTTP media body")
        }
        activeResponse = response
        input = stream
        return stream
    }

    @Synchronized override fun read(buffer: ByteBuffer): Int {
        if (cancelled) return -1
        if (!buffer.hasRemaining()) return 0
        return try {
            val source = input ?: openAt(offset)
            val bytes = ByteArray(minOf(buffer.remaining(), 64 * 1024))
            val count = source.read(bytes)
            if (count > 0) {
                buffer.put(bytes, 0, count)
                offset += count
            }
            count.coerceAtLeast(0)
        } catch (_: IOException) { -1 }
    }

    @Synchronized override fun seek(position: Long): Long {
        if (cancelled || position < 0 || (knownSize >= 0L && position > knownSize)) return -1
        if (position != offset) {
            releaseResponse()
            offset = position
        }
        return offset
    }

    @Synchronized override fun size(): Long = knownSize

    // Cancellation must not wait for the read lock: it can interrupt blocking IO.
    override fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    @Synchronized override fun close() {
        cancelled = true
        releaseResponse()
    }

    private fun releaseResponse() {
        input?.close()
        activeResponse?.close()
        input = null
        activeResponse = null
        activeCall = null
    }
}
