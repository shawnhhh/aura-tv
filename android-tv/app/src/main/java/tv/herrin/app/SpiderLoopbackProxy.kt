package tv.herrin.app

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CatVod Java Spider.proxy() bridge, strictly loopback-only in the :spider process.
 * Only the currently selected site, a random port and a per-session 192-bit token
 * can access the route. No generic URL forwarding or unauthenticated LAN API.
 *
 * This is distinct from the separately audited MediaLoopbackProxy upstream service.
 * The Spider supplies InputStream bytes; HERRIN never fetches an arbitrary URL.
 *
 * Proxy response/dispatch semantics: FongMi/TV GPL-3.0, commit
 * 5e191aaced194e5cac5668cc91908ecb65336222, server/process/Proxy.java and
 * api/loader/BaseLoader.java. HERRIN host adaptation (2026-11-01): authenticated
 * loopback transport, bounded concurrency, credential header filtering and
 * explicit stream cancellation. See docs/m467/spider-proxy.md.
 */
internal class SpiderLoopbackProxy(
    private val siteKey: String,
    private val staticHandler: ((Map<String, String>) -> Array<out Any?>?)? = null,
    private val errorStatus: (Throwable) -> Int = { 502 },
    private val handler: (Map<String, String>) -> Array<out Any?>?
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
    // HLS/DASH and range-seek requests overlap; two workers could remain
    // occupied on large streams, blocking all following segment requests.
    private val workers = ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
        ArrayBlockingQueue(24)) { runnable ->
        Thread(runnable, "Herrin Spider proxy response").apply { isDaemon = true }
    }
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val streams = java.util.concurrent.ConcurrentHashMap.newKeySet<InputStream>()
    val port: Int get() = server.localPort
    val isOpen: Boolean get() = !closed.get() && !server.isClosed
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    val url: String get() = "http://127.0.0.1:" + port + "/proxy?session=" + token +
        "&do=jar&siteKey=" + URLEncoder.encode(siteKey, "UTF-8")
    private val acceptor = Thread({
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            try {
                workers.execute {
                    try {
                        socket.use { serve(it) }
                    } catch (_: Exception) {
                        // Plugin exceptions and request credentials must never enter logs.
                    } finally {
                        sockets.remove(socket)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                sockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }, "Herrin Spider local proxy").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 12_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val request = try { parse(input) } catch (_: Exception) {
            failure(output, 400)
            return
        }
        if (request == null) return
        val method = request.first
        val params = request.second
        val path = request.third
        if (method != "GET" && method != "HEAD") {
            failure(output, 405)
            return
        }
        if (params["session"] != token ||
            (params["siteKey"] != null && params["siteKey"] != siteKey) || closed.get() ||
            (path == "/health" && params["siteKey"] != siteKey) ||
            (path == "/proxy" && params["do"] != "jar")) {
            failure(output, 403)
            return
        }
        if (path == "/health") {
            output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return
        }
        val response = try {
            // Only the active Spider's own callback; no unrestricted URL fetching.
            // FongMi Java Spider supports either a 3-4 element array or one
            // NanoHTTPD.Response object. Adapt only its public response API;
            // do not run its own HTTP server or reuse its socket/session.
            val isStatic = !params.containsKey("siteKey") || params["__herrin_static"] == "1"
            val forwarded = params.filterKeys {
                it != "session" && it != "__herrin_static" && !(isStatic && it == "siteKey")
            }
            val callback = if (isStatic) staticHandler else handler
            adaptResponse(callback?.invoke(forwarded))
        } catch (error: Throwable) {
            // Match upstream RuntimeModule absence classification while keeping
            // exception messages, paths and plugin credentials off the wire.
            failure(output, errorStatus(error))
            return
        }
        if (response == null || response.size < 3 ||
            response[0] !is Number || response[1] !is String ||
            response[2] !is InputStream) {
            failure(output, 502)
            return
        }
        val status = (response[0] as Number).toInt()
        val mime = (response[1] as String).trim()
        val body = response[2] as InputStream
        streams.add(body)
        try { body.use { stream ->
            if (status !in 100..599 || mime.length !in 1..128 ||
                mime.any { it == '\r' || it == '\n' || it.code < 32 }) {
                failure(output, 502)
                return
            }
            val headers = (response.getOrNull(3) as? Map<*, *>).orEmpty()
            val safeHeaders = headers.entries.mapNotNull { (name, value) ->
                val key = name as? String ?: return@mapNotNull null
                val text = value as? String ?: return@mapNotNull null
                if (key.lowercase() !in RESPONSE_HEADERS || text.length !in 1..4096 ||
                    text.any { it == '\r' || it == '\n' || it.code < 32 })
                    null else key to text
            }
            val location = safeHeaders.firstOrNull { it.first.equals("location", true) }?.second
            if (status in setOf(301, 302, 303, 307, 308) && (location == null || !safeRedirect(location))) {
                failure(output, 502)
                return
            }
            val noBody = method == "HEAD" || status in 100..199 || status == 204 || status == 304
            val head = buildString {
                append("HTTP/1.1 "); append(status); append(" Proxy Response\r\n")
                append("Content-Type: "); append(mime); append("\r\n")
                append("Connection: close\r\n")
                if (noBody) append("Content-Length: 0\r\n")
                else append("Transfer-Encoding: chunked\r\n")
                for ((key, value) in safeHeaders) {
                    append(key); append(": "); append(value); append("\r\n")
                }
                append("Cache-Control: no-store\r\n\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            if (noBody) return
            val buffer = ByteArray(16 * 1024)
            // A single MKV can exceed 10 GiB. The old 512 MiB ceiling
            // silently truncated large sources and broke range seeking.
            // Stream with a fixed 16 KiB buffer until EOF or cancellation;
            // bound memory, sockets and concurrent workers instead of bytes.
            while (!closed.get()) {
                val size = stream.read(buffer)
                if (size < 0) break
                if (size == 0) continue
                output.write(Integer.toHexString(size).toByteArray(StandardCharsets.US_ASCII))
                output.write(CRLF)
                output.write(buffer, 0, size)
                output.write(CRLF)
            }
            if (!closed.get()) {
                output.write(END_CHUNKS)
                output.flush()
            }
        } } finally { streams.remove(body) }
    }

    private fun adaptResponse(raw: Array<out Any?>?): Array<out Any?>? {
        // Execute upstream createResponse, including custom status handling,
        // failed-stream closure and X-TV-Proxy-Error on origin failures.
        val nano = FongMiProxyResponse.createResponse(raw)
        val headers = linkedMapOf<String, String>()
        for (key in RESPONSE_HEADERS) {
            val value = nano.getHeader(key)
            if (!value.isNullOrBlank()) headers[key] = value
        }
        return arrayOf(
            nano.status?.requestStatus ?: 502,
            nano.mimeType ?: "application/octet-stream",
            nano.data ?: java.io.ByteArrayInputStream(byteArrayOf()),
            headers
        )
    }

    private fun safeRedirect(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (!uri.isAbsolute) return@runCatching !value.startsWith("//") && !value.contains("\\")
        uri.scheme.lowercase() in setOf("http", "https") && uri.userInfo == null &&
            uri.host != null && uri.host.lowercase() !in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }.getOrDefault(false)

    private fun failure(out: OutputStream, status: Int) {
        val code = if (status in setOf(400, 403, 405, 502, 503)) status else 502
        val body = "Local Spider proxy rejected the request"
        val bytes = body.toByteArray(StandardCharsets.US_ASCII)
        val text = "HTTP/1.1 " + code + " Error\r\nContent-Type: text/plain\r\n" +
            "Content-Length: " + bytes.size + "\r\nConnection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        runCatching {
            out.write(text.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    /**
     * Original JARs append "?do=jar" to Proxy.getUrl(), which had no query in
     * FongMi. HERRIN adds a session query. Repair that one delimiter only after
     * matching this exact capability; arbitrary malformed queries still fail.
     */
    internal fun compatibleQuery(raw: String?): String? {
        val prefix = "session=$token?"
        return if (raw?.startsWith(prefix) == true)
            "session=$token&" + raw.substring(prefix.length) else raw
    }

    private fun parse(input: InputStream): Triple<String, Map<String, String>, String>? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1" ||
            parts[1].length > 4096) throw IllegalArgumentException("Invalid HTTP request")
        var host: String? = null
        var origin = false
        var range: String? = null
        var ended = false
        val headers = linkedMapOf<String, String>()
        for (index in 0 until MAX_HEADERS) {
            val line = readLine(input) ?: throw IllegalArgumentException("Invalid headers")
            if (line.isEmpty()) { ended = true; break }
            val indexOfColon = line.indexOf(':')
            if (indexOfColon !in 1..80) throw IllegalArgumentException("Invalid header")
            val key = line.substring(0, indexOfColon).lowercase()
            val value = line.substring(indexOfColon + 1).trim()
            if (!key.matches(Regex("[!#$%&'*+.^_`|~0-9a-z-]+")) ||
                value.any { it.code < 32 && it != '\t' }) throw IllegalArgumentException("Invalid header")
            if (key in REQUEST_HEADERS) headers[key] = value
            if (key == "host") host = value
            if (key == "origin") origin = true
            // FongMi forwards request headers to Spider.proxy(params). Media3
            // uses Range for seekable streams. Forward ONLY bounded byte ranges,
            // never Cookie/Authorization or arbitrary caller-supplied headers.
            if (key == "range") {
                if (range != null || value.length > 48 ||
                    !value.matches(Regex("(?i)bytes=(?:[0-9]{1,18}-[0-9]{0,18}|-[0-9]{1,18})")))
                    throw IllegalArgumentException("Invalid byte range")
                range = value
            }
        }
        if (!ended) throw IllegalArgumentException("Too many headers")
        if (origin || host != "127.0.0.1:" + port) throw IllegalArgumentException("Wrong host")
        val target = URI(parts[1])
        if (target.path !in setOf("/proxy", "/health") || target.fragment != null)
            throw IllegalArgumentException("Unsupported path")
        val rawQuery = compatibleQuery(target.rawQuery) ?: throw IllegalArgumentException("Missing session")
        if (rawQuery.length > MAX_QUERY) throw IllegalArgumentException("Oversized query")
        val entries = rawQuery.split('&')
        if (entries.size !in 1..24) throw IllegalArgumentException("Too many params")
        val params = LinkedHashMap<String, String>()
        for (item in entries) {
            val eq = item.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) item else item.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(if (eq < 0) "" else item.substring(eq + 1), "UTF-8")
            if (key.isBlank() || key.length > 80 || key in params)
                throw IllegalArgumentException("Invalid parameter")
            params[key] = value
        }
        // HTTP request headers override same-named query keys, as in CatVod
        // NanoHTTPD. A plugin sees range via params["range"] (lowercase).
        params.putAll(headers)
        range?.let { params["range"] = it }
        return Triple(parts[0], params, target.path)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_LINE) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else
                throw IllegalArgumentException("Truncated headers")
            if (next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            if (next < 32 && next != 13 && next != 9)
                throw IllegalArgumentException("Invalid control byte")
            bytes.write(next)
        }
        throw IllegalArgumentException("Line exceeds bound")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        streams.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        acceptor.interrupt()
    }

    private companion object {
        const val MAX_HEADERS = 32
        const val MAX_LINE = 4_096
        const val MAX_QUERY = 4_096
        val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        val END_CHUNKS = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val REQUEST_HEADERS = setOf("range", "if-range", "if-match", "if-none-match",
            "if-modified-since", "if-unmodified-since", "accept", "accept-encoding",
            "accept-language", "user-agent", "referer")
        // The proxy must preserve media HTTP semantics without exporting a
        // plugin's cookies, authentication challenges or private custom headers.
        val RESPONSE_HEADERS = setOf("cache-control", "content-range", "accept-ranges",
            "etag", "last-modified", "location", "content-encoding", "content-language",
            "content-disposition", "expires", "vary", "retry-after", "x-tv-proxy-error")
    }
}
