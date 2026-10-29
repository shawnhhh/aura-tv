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
 */
internal class SpiderLoopbackProxy(
    private val siteKey: String,
    private val handler: (Map<String, String>) -> Array<out Any?>?
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
        ArrayBlockingQueue(8)) { runnable ->
        Thread(runnable, "Herrin Spider proxy response").apply { isDaemon = true }
    }
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    val port: Int get() = server.localPort
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
        if (method != "GET" && method != "HEAD") {
            failure(output, 405)
            return
        }
        if (params["session"] != token || params["do"] != "jar" ||
            params["siteKey"] != siteKey || closed.get()) {
            failure(output, 403)
            return
        }
        val response = try {
            // Only the active Spider's own callback; no unrestricted URL fetching.
            handler(params.filterKeys { it != "session" })
        } catch (_: Throwable) {
            failure(output, 502)
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
        body.use { stream ->
            if (status !in listOf(200, 206) || mime.length !in 1..128 ||
                mime.any { it == '\r' || it == '\n' || it.code < 32 }) {
                failure(output, 502)
                return
            }
            val headers = (response.getOrNull(3) as? Map<*, *>).orEmpty()
            val safeHeaders = headers.entries.mapNotNull { (name, value) ->
                val key = name as? String ?: return@mapNotNull null
                val text = value as? String ?: return@mapNotNull null
                if (key.lowercase() !in setOf("cache-control", "content-range", "accept-ranges",
                        "etag", "last-modified") || text.length !in 1..512 ||
                    text.any { it == '\r' || it == '\n' || it.code < 32 })
                    null else key to text
            }.take(8)
            val head = buildString {
                append(if (status == 206) "HTTP/1.1 206 Partial Content\r\n"
                    else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: "); append(mime); append("\r\n")
                append("Connection: close\r\n")
                if (method == "HEAD") append("Content-Length: 0\r\n")
                else append("Transfer-Encoding: chunked\r\n")
                for ((key, value) in safeHeaders) {
                    append(key); append(": "); append(value); append("\r\n")
                }
                append("Cache-Control: no-store\r\n\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            if (method == "HEAD") return
            val buffer = ByteArray(16 * 1024)
            var streamed = 0L
            while (!closed.get() && streamed < MAX_RESPONSE_BYTES) {
                val size = stream.read(buffer, 0,
                    minOf(buffer.size.toLong(), MAX_RESPONSE_BYTES - streamed).toInt())
                if (size < 0) break
                if (size == 0) continue
                output.write(Integer.toHexString(size).toByteArray(StandardCharsets.US_ASCII))
                output.write(CRLF)
                output.write(buffer, 0, size)
                output.write(CRLF)
                streamed += size
            }
            if (!closed.get() && streamed < MAX_RESPONSE_BYTES) {
                output.write(END_CHUNKS)
                output.flush()
            }
        }
    }

    private fun failure(out: OutputStream, status: Int) {
        val code = if (status in setOf(400, 403, 405, 502)) status else 502
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

    private fun parse(input: InputStream): Pair<String, Map<String, String>>? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1" ||
            parts[1].length > 4096) throw IllegalArgumentException("Invalid HTTP request")
        var host: String? = null
        var origin = false
        for (index in 0 until MAX_HEADERS) {
            val line = readLine(input) ?: throw IllegalArgumentException("Invalid headers")
            if (line.isEmpty()) break
            val indexOfColon = line.indexOf(':')
            if (indexOfColon !in 1..80) throw IllegalArgumentException("Invalid header")
            val key = line.substring(0, indexOfColon).lowercase()
            val value = line.substring(indexOfColon + 1).trim()
            if (key == "host") host = value
            if (key == "origin") origin = true
        }
        if (origin || host != "127.0.0.1:" + port) throw IllegalArgumentException("Wrong host")
        val target = URI(parts[1])
        if (target.path != "/proxy" || target.fragment != null)
            throw IllegalArgumentException("Unsupported path")
        val rawQuery = target.rawQuery ?: throw IllegalArgumentException("Missing session")
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
        return parts[0] to params
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
        workers.shutdownNow()
        acceptor.interrupt()
    }

    private companion object {
        const val MAX_HEADERS = 32
        const val MAX_LINE = 4_096
        const val MAX_QUERY = 4_096
        const val MAX_RESPONSE_BYTES = 512L * 1024 * 1024
        val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        val END_CHUNKS = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
