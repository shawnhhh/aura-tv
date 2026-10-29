package tv.herrin.app

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A closable, single-source playback session. Does not start automatically or touch player UI. */
class MediaLoopbackProxy private constructor(
    private val policy: MediaProxyPolicy,
    private val upstream: OkHttpClient,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val token = randomId()
    private val resources = LinkedHashMap<String, HttpUrl>()
    private val resourceIds = HashMap<HttpUrl, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "herrin-media-proxy-worker").apply { isDaemon = true } })
    private val acceptor: Thread
    private val rootId = register(policy.source)
    val playbackUrl: String get() = localUrl(rootId)
    val isClosed: Boolean get() = closed.get()

    init {
        acceptor = Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                if (closed.get()) { sockets.remove(socket); socket.close(); break }
                try {
                    workers.execute { serve(socket) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }, "herrin-media-proxy-accept").apply { isDaemon = true; start() }
    }

    override fun toString(): String = "MediaLoopbackProxy(session redacted, closed=${closed.get()})"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        sockets.forEach { try { it.close() } catch (_: Exception) { } }
        sockets.clear()
        upstream.dispatcher.cancelAll()
        upstream.dispatcher.executorService.shutdownNow()
        upstream.connectionPool.evictAll()
        workers.shutdownNow()
        // ServerSocket.close may defer the native listening fd close until a blocking accept exits.
        // Joining the acceptor makes session shutdown observable before returning to the owner.
        if (Thread.currentThread() !== acceptor) {
            try { acceptor.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(resources) { resources.clear(); resourceIds.clear() }
    }

    private fun serve(socket: Socket) {
        var writer: LocalResponse? = null
        try {
            run {
                socket.soTimeout = 10_000
                writer = LocalResponse(socket.getOutputStream())
                val request = readRequest(socket.getInputStream())
                if (request.method !in setOf("GET", "HEAD")) { writer!!.error(405); return }
                if (request.headers["host"] != "127.0.0.1:${listener.localPort}" || "origin" in request.headers) {
                    writer!!.error(403); return
                }
                if (request.headers["content-length"]?.let { it != "0" } == true || "transfer-encoding" in request.headers) {
                    writer!!.error(400); return
                }
                val parts = request.path.split('/')
                if (parts.size != 3 || !MessageDigest.isEqual(parts[1].toByteArray(), token.toByteArray())) {
                    writer!!.error(403); return
                }
                val target = synchronized(resources) { resources[parts[2]] }
                if (target == null) { writer!!.error(404); return }
                val range = request.headers["range"]
                if (range != null && !validRange(range)) { writer!!.error(416); return }
                fetch(target, request.method, range).use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null || request.method == "HEAD") {
                        writer!!.headers(response.code, responseHeaders(response, false))
                        return
                    }
                    val encoding = response.header("Content-Encoding").orEmpty()
                    if (encoding.isNotEmpty() && !encoding.equals("identity", true)) { writer!!.error(502); return }
                    val type = response.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
                    val source = body.source()
                    val preview = source.peek()
                    preview.request(7)
                    val prefix = preview.readByteArray(minOf(7L, preview.buffer.size))
                    val hls = type.contains("mpegurl") || response.request.url.encodedPath.endsWith(".m3u8", true) ||
                        prefix.toString(Charsets.UTF_8).startsWith("#EXTM3U")
                    if (type.contains("dash+xml") || response.request.url.encodedPath.endsWith(".mpd", true) ||
                        prefix.toString(Charsets.UTF_8).trimStart().startsWith("<")) { writer!!.error(502); return }
                    if (hls) {
                        if (range != null || response.code != 200) { writer!!.error(416); return }
                        val bounded = okio.Buffer()
                        while (bounded.size <= MAX_PLAYLIST_BYTES) {
                            if (source.read(bounded, minOf(8192L, MAX_PLAYLIST_BYTES + 1L - bounded.size)) == -1L) break
                        }
                        val bytes = bounded.readByteArray()
                        if (bytes.size > MAX_PLAYLIST_BYTES) { writer!!.error(502); return }
                        val rewritten = rewritePlaylist(bytes.toString(Charsets.UTF_8), response.request.url)
                            .toByteArray(Charsets.UTF_8)
                        writer!!.headers(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to rewritten.size.toString()))
                        writer!!.output.write(rewritten)
                    } else {
                        writer!!.headers(response.code, responseHeaders(response, true))
                        source.inputStream().copyTo(writer!!.output, 32768)
                    }
                }
            }
        } catch (_: Exception) {
            // Exceptions may contain upstream URLs or credentials: never log or send their messages.
            try { if (writer?.committed == false) writer?.error(502) } catch (_: Exception) { }
        } finally {
            sockets.remove(socket)
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun fetch(initial: HttpUrl, method: String, range: String?): Response {
        var url = initial
        repeat(4) { attempt ->
            check(!closed.get()) { "Media proxy session closed" }
            val request = Request.Builder().url(url).method(method, null)
            policy.requestHeaders.forEach { (name, value) -> request.header(name, value) }
            request.header("Accept-Encoding", "identity")
            range?.let { request.header("Range", it) }
            val response = upstream.newCall(request.build()).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            response.use {
                require(attempt < 3) { "Redirect limit exceeded" }
                url = policy.resolve(url, response.header("Location") ?: error("Missing redirect location"))
            }
        }
        error("Redirect limit exceeded")
    }

    private fun rewritePlaylist(text: String, base: HttpUrl): String {
        require(text.lineSequence().firstOrNull()?.trimEnd('\r') == "#EXTM3U") { "Invalid HLS playlist" }
        require(!text.contains("{$")) { "HLS variables not supported" }
        val pending = mutableListOf<HttpUrl>()
        fun reference(value: String): String {
            require(pending.size < MAX_RESOURCES) { "HLS reference limit exceeded" }
            pending += policy.resolve(base, value)
            return "__HERRIN_RESOURCE_${pending.lastIndex}__"
        }
        val lines = text.lines().map { raw ->
            val line = raw.trimEnd('\r')
            require(line.length <= 8192) { "HLS line too long" }
            require(!line.startsWith("#EXT-X-CONTENT-STEERING")) { "HLS steering not supported" }
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                require(line == "#EXT-X-KEY:METHOD=NONE") { "Encrypted HLS not supported by proxy" }
            }
            when {
                line.isBlank() -> line
                !line.startsWith('#') -> {
                    reference(line)
                }
                else -> {
                    require(!Regex("(?<![A-Z-])[A-Z-]+-URI=").containsMatchIn(line)) { "Unsupported HLS URI attribute" }
                    val replaced = Regex("(?<![A-Z0-9-])URI=\"([^\"]*)\"").replace(line) { match ->
                        "URI=\"${reference(match.groupValues[1])}\""
                    }
                    require(!Regex("(?<![A-Z0-9-])URI=(?!\")").containsMatchIn(replaced)) { "Invalid HLS URI attribute" }
                    replaced
                }
            }
        }
        synchronized(resources) {
            check(!closed.get()) { "Media proxy session closed" }
            require(resources.size + pending.distinct().count { it !in resourceIds } <= MAX_RESOURCES) { "HLS resource limit exceeded" }
            // Only register after the complete manifest has passed origin and encryption checks.
            val local = pending.map { localUrl(register(it)) }
            return lines.joinToString("\n") { line ->
                Regex("__HERRIN_RESOURCE_(\\d+)__").replace(line) { match -> local[match.groupValues[1].toInt()] }
            }
        }
    }

    private fun register(url: HttpUrl): String = synchronized(resources) {
        check(!closed.get()) { "Media proxy session closed" }
        resourceIds.getOrPut(url) {
            check(resources.size < MAX_RESOURCES)
            randomId().also { resources[it] = url }
        }
    }

    private fun localUrl(id: String): String = "http://127.0.0.1:${listener.localPort}/$token/$id"

    private data class LocalRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): LocalRequest {
        var count = 0
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val value = input.read()
                require(value >= 0 && ++count <= 16384) { "Invalid local request" }
                if (value == 10) {
                    require(previous == 13) { "Invalid local request" }
                    return bytes.toString("US-ASCII").dropLast(1)
                }
                require(value in 32..126 || value == 13) { "Invalid local request" }
                require(previous != 13) { "Invalid local request" }
                bytes.write(value); previous = value
            }
        }
        val first = line().split(' ')
        require(first.size == 3 && first[2] in setOf("HTTP/1.0", "HTTP/1.1") && first[1].startsWith('/')) { "Invalid local request" }
        val headers = LinkedHashMap<String, String>()
        repeat(64) {
            val header = line()
            if (header.isEmpty()) return LocalRequest(first[0], first[1], headers)
            val colon = header.indexOf(':')
            require(colon > 0) { "Invalid local header" }
            val name = header.substring(0, colon).lowercase(java.util.Locale.ROOT)
            require(name.all { it in 'a'..'z' || it == '-' } && name !in headers) { "Invalid local header" }
            headers[name] = header.substring(colon + 1).trim()
        }
        error("Too many local headers")
    }

    private class LocalResponse(val output: OutputStream) {
        var committed = false
        fun headers(code: Int, headers: Map<String, String>) {
            require(code in 100..599)
            committed = true
            val result = buildString {
                append("HTTP/1.1 $code Media\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
                headers.forEach { (key, value) ->
                    if (value.length <= 8192 && value.all { it in ' '..'~' }) append("$key: $value\r\n")
                }
                append("\r\n")
            }
            output.write(result.toByteArray(Charsets.US_ASCII))
        }
        fun error(code: Int) = headers(code, mapOf("Content-Length" to "0"))
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 1024 * 1024
        private const val MAX_RESOURCES = 2048
        private val random = SecureRandom()
        private fun randomId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))

        /** Own and close the returned session when playback ends, changes source, or the owner stops. */
        fun start(policy: MediaProxyPolicy): MediaLoopbackProxy = MediaLoopbackProxy(policy,
            OkHttpClient.Builder().dns(MediaProxyPublicDns()).proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build())

        /** Test seam for a controlled upstream server; never exposed as a production policy switch. */
        internal fun startForTest(policy: MediaProxyPolicy, client: OkHttpClient): MediaLoopbackProxy =
            MediaLoopbackProxy(policy, client.newBuilder().followRedirects(false).followSslRedirects(false).build())

        private fun responseHeaders(response: Response, includeLength: Boolean): Map<String, String> = buildMap {
            for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Retry-After")) {
                response.header(name)?.let { put(name, it) }
            }
            if (includeLength || response.request.method == "HEAD") {
                response.header("Content-Length")?.let { put("Content-Length", it) }
            }
        }

        internal fun validRange(value: String): Boolean {
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: return false
            val (start, end) = match.destructured
            if (start.isEmpty()) return end.toLongOrNull()?.let { it > 0 } == true
            val first = start.toLongOrNull() ?: return false
            return end.isEmpty() || end.toLongOrNull()?.let { it >= first } == true
        }
    }
}
