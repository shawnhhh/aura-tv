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
    private val strictHttpFailure: Boolean = false,
    private val evidence: (String, String) -> Unit = { _, _ -> }
) {
    enum class Result {
        MEDIA, INVALID_URL, UNAUTHORIZED_LOCAL, HTTP_ERROR, LOGIN_OR_TEXT,
        INVALID_RANGE, EMPTY_BODY, INVALID_MANIFEST, NETWORK_ERROR
    }

    suspend fun canRead(media: PlaybackMedia): Boolean = probe(media) == Result.MEDIA

    suspend fun probe(media: PlaybackMedia): Result {
        require(media.kernelPlayback == null) { "Whole-kernel selections must not enter the legacy media probe" }
        val began = System.nanoTime()
        val ioPhase = java.util.concurrent.atomic.AtomicReference("NO_CONNECT_EVENT")
        val observedClient = client.newBuilder().eventListener(object: okhttp3.EventListener() {
            override fun dnsStart(call: okhttp3.Call, domainName: String) { ioPhase.set("DNS"); evidence("MEDIA_DNS_STARTED", "") }
            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { evidence("MEDIA_DNS_FINISHED", "") }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                ioPhase.set("CONNECT")
                evidence("MEDIA_CONNECT_STARTED", "")
                evidence(if(proxy.type()==java.net.Proxy.Type.DIRECT) "MEDIA_TRANSPORT_DIRECT" else "MEDIA_TRANSPORT_VIA_PROXY", "")
            }
            override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
                ioPhase.set("REQUEST_HEADERS"); evidence("MEDIA_CONNECT_FINISHED", "")
            }
            override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) {
                evidence("MEDIA_CONNECT_FAILED", "")
            }
            override fun requestHeadersEnd(call: okhttp3.Call, request: Request) {
                ioPhase.set("RESPONSE_HEADERS"); evidence("MEDIA_REQUEST_HEADERS_SENT", "")
            }
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                ioPhase.set("RESPONSE_BODY"); evidence("MEDIA_RESPONSE_HEADERS_RECEIVED", "")
            }
            override fun responseBodyStart(call: okhttp3.Call) { evidence("MEDIA_RESPONSE_BODY_STARTED", "") }
            override fun secureConnectStart(call: okhttp3.Call) { ioPhase.set("TLS"); evidence("MEDIA_TLS_STARTED", "") }
            override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { evidence("MEDIA_TLS_FINISHED", "") }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                evidence(if (call.isCanceled()) "MEDIA_SOCKET_CANCELLED" else "MEDIA_CALL_FAILED", "")
            }
        }).build()
        var receivedBytes = 0
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
                    // Media3/FongMi requests HLS manifests without Range. A
                    // Spider proxy can dispatch Range as a different operation;
                    // probing it must not change the provider's manifest request.
                    // Reads below remain bounded even for a full 200 response.
                    if (!tv.herrin.app.nativeplayback.NativeFongMiRoute.isNativeProxy(url) &&
                        media.declaredMime != PlaybackFormatRetry.HLS &&
                        !target.path.orEmpty().lowercase().endsWith(".m3u8")) {
                        header("Range", "bytes=0-$PROBE_RANGE_END")
                    }
                    header("Accept-Encoding", "identity")
                }.build()
            } catch (_: IllegalArgumentException) {
                return Result.INVALID_URL
            }
            evidence("MEDIA_REQUEST", "HOP=$hop")
            evidence(if (isLoopback(target)) "MEDIA_TARGET_PROXY" else "MEDIA_TARGET_REMOTE", "")
            val result = try { readSourceResponse(observedClient,request) { reply ->
                evidence("MEDIA_HEADERS", "MS=${((System.nanoTime()-began)/1_000_000).coerceAtMost(99999)}")
                evidence("MEDIA_HTTP", "STATUS=${reply.code}")
                evidence(if (reply.header("Transfer-Encoding").equals("chunked", true)) "MEDIA_CHUNKED" else "MEDIA_NOT_CHUNKED", "")
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
                evidence("MEDIA_TYPE_" + when {
                    contentType.contains("mpegurl") -> "HLS"
                    contentType.startsWith("video/") -> "VIDEO"
                    contentType.startsWith("text/") -> "TEXT"
                    contentType.contains("json") -> "JSON"
                    else -> "OTHER"
                }, "")
                val buffer = ByteArray((PROBE_RANGE_END + 1).toInt())
                val size = try {
                    val stream=reply.body?.byteStream() ?: return@readSourceResponse Result.EMPTY_BODY
                    var received=0
                    while(received<buffer.size) {
                        val count=stream.read(buffer,received,buffer.size-received)
                        if(count<0)break
                        if(count==0)continue
                        if (received == 0) evidence("MEDIA_FIRST_BYTE", "MS=${((System.nanoTime()-began)/1_000_000).coerceAtMost(99999)}")
                        received+=count
                        receivedBytes = received
                        val prefix = buffer.copyOf(received).toString(Charsets.UTF_8)
                            .trimStart('\uFEFF',' ','\n','\r','\t')
                        if (prefix.startsWith("#EXTM3U\n") || prefix.startsWith("#EXTM3U\r\n") ||
                            MediaProbePrefix.identified(buffer, received)) break
                    }
                    received
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    evidence("MEDIA_BYTES", "COUNT=$receivedBytes")
                    // This inner catch returns before the outer phase reporter.
                    // Preserve the actual failure phase without changing I/O or its result.
                    evidence("MEDIA_FAILURE_RESPONSE_BODY", "")
                    evidence("MEDIA_BODY_IO_" + ioCode(error), "")
                    evidence("MEDIA_IO_" + ioCode(error), "")
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
            } } catch(cancel:CancellationException){evidence("MEDIA_CANCELLED_CALLER", "");throw cancel} catch(error:Exception){
                evidence("MEDIA_FAILURE_" + ioPhase.get(), "")
                evidence("MEDIA_IO_" + ioCode(error), "")
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

    private fun ioCode(error: Exception) = when(error) {
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "TIMEOUT"
        is java.net.UnknownHostException -> "DNS"
        is javax.net.ssl.SSLException -> "TLS"
        is java.net.ConnectException -> "CONNECT"
        is java.io.EOFException -> "EOF"
        else -> "OTHER"
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
