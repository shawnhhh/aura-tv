package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A sidecar explicitly supplied by the selected source, never a guessed/search service. */
data class DanmakuSource(val name: String, val url: String) {
    // Source URLs can contain private query strings; do not expose them to diagnostics.
    override fun toString() = "DanmakuSource(name=$name)"
}
data class DanmakuComment(val timeMs: Long, val text: String, val mode: Int = 1,
                          val color: Int = 0xffffff, val size: Float = 25f)
data class DanmakuSettings(val enabled: Boolean = false, val opacity: Float = .75f,
                           val speed: Float = 1f, val fontSize: Float = 24f) {
    fun normalized() = copy(opacity = opacity.takeIf { it.isFinite() }?.coerceIn(.25f, 1f) ?: .75f,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
        fontSize = fontSize.takeIf { it.isFinite() }?.coerceIn(16f, 40f) ?: 24f)
}

/** FongMi Result.danmaku: URL, JSON-encoded [{name,url}], or [{name,url}].
 * File format: XML <d p="seconds,mode,size,rgb,...">text</d>. No global API is inferred.
 * Implemented independently from documented wire fields; see docs/M4_37_DANMAKU_ZH.md.
 */
object DanmakuProtocol {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_COMMENTS = 10000
    fun decodeSources(raw: Any?): List<DanmakuSource> = try {
        val values = when (raw) {
            is JSONArray -> raw
            is String -> if (raw.trim().startsWith("[")) JSONArray(raw) else
                JSONArray().put(JSONObject().put("url", raw))
            else -> JSONArray()
        }
        (0 until minOf(values.length(), 8)).mapNotNull { i ->
            val obj = values.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url").trim()
            if (!isSafeUrl(url)) return@mapNotNull null
            DanmakuSource(obj.optString("name").trim().take(40).ifBlank { "弹幕 ${i + 1}" }, url)
        }.distinctBy { it.url }
    } catch (_: Exception) { emptyList() }

    fun encodeSources(sources: List<DanmakuSource>): JSONArray = JSONArray().also { array ->
        sources.take(8).filter { isSafeUrl(it.url) }.forEach {
            array.put(JSONObject().put("name", it.name.take(40)).put("url", it.url))
        }
    }

    fun isSafeUrl(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        url.length <= 4096 && uri.scheme.equals("https", true) && uri.userInfo == null &&
            uri.fragment == null && host.isNotBlank() && host != "localhost" &&
            !host.endsWith(".localhost") && !host.endsWith(".local") &&
            !host.contains(':') && !host.matches(Regex("[0-9.]+"))
    } catch (_: Exception) { false }

    /** Reject DTD/entities before parser construction on both Android and desktop JVM. */
    fun parseXml(xml: String): List<DanmakuComment> {
        require(xml.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "弹幕数据过大" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "弹幕格式无效" }
        val result = ArrayList<DanmakuComment>()
        var parameters: String? = null
        val text = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, name: String?, attrs: Attributes) {
                if (name == "d" || local == "d") {
                    parameters = attrs.getValue("p")
                    text.setLength(0)
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (parameters != null && text.length < 513) text.append(chars, start, minOf(length, 513 - text.length))
            }
            override fun endElement(uri: String?, local: String?, name: String?) {
                if (name != "d" && local != "d") return
                val parts = parameters?.split(',').orEmpty()
                parameters = null
                if (result.size >= MAX_COMMENTS || parts.size < 4) return
                val seconds = parts[0].toDoubleOrNull() ?: return
                val mode = parts[1].toIntOrNull() ?: return
                val size = parts[2].toFloatOrNull() ?: return
                val color = parts[3].toIntOrNull() ?: return
                val message = text.toString().trim().filter { it >= ' ' || it == '\n' }.replace('\n', ' ')
                if (!seconds.isFinite() || seconds < 0 || seconds > 86400 ||
                    mode !in setOf(1, 4, 5, 6) || !size.isFinite() || size !in 1f..96f ||
                    color !in 0..0xffffff || message.isBlank() || message.length > 512) return
                result.add(DanmakuComment((seconds * 1000).toLong(), message, mode, color, size))
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // Some Android SAX implementations do not recognize these options. The lexical DTD
        // rejection and empty resolver above remain mandatory independent protections.
        listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities").forEach { feature ->
            runCatching { factory.setFeature(feature, false) }
        }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return result.distinct().sortedBy { it.timeMs }
    }
}

/** Pure position-based clock: pause, backwards seek and episode changes cannot leave stale items. */
object DanmakuTimeline {
    data class Visible(val comment: DanmakuComment, val progress: Float, val lane: Int)
    fun visible(comments: List<DanmakuComment>, positionMs: Long, settings: DanmakuSettings): List<Visible> {
        if (!settings.enabled || positionMs < 0) return emptyList()
        val duration = (8000 / settings.normalized().speed).toLong()
        // Sorted input permits a binary lookup, so each frame scans only the visible window.
        var low = 0
        var high = comments.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (comments[mid].timeMs <= positionMs - duration) low = mid + 1 else high = mid
        }
        val lanes = HashSet<Pair<Int, Int>>()
        return comments.subList(low, comments.size).asSequence().takeWhile { it.timeMs <= positionMs }
            .mapIndexedNotNull { index, item ->
                val lane = (low + index) % 6
                if (!lanes.add(item.mode to lane)) null else
                    Visible(item, (positionMs - item.timeMs).toFloat() / duration, lane)
            }.take(24).toList()
    }
}

/** Isolated best-effort sidecar transport; no playback headers/cookies, redirects or implicit retries. */
class DanmakuRepository(private val fetch: (String) -> String = ::fetchDanmaku) {
    fun load(source: DanmakuSource, explicitlyEnabled: Boolean): List<DanmakuComment> {
        if (!explicitlyEnabled || !DanmakuProtocol.isSafeUrl(source.url)) return emptyList()
        return try { DanmakuProtocol.parseXml(fetch(source.url)) } catch (_: Exception) { emptyList() }
    }
}
private fun fetchDanmaku(url: String): String {
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        require(response.isSuccessful) { "弹幕暂不可用" }
        val body = requireNotNull(response.body)
        require(body.contentLength() <= DanmakuProtocol.MAX_BYTES) { "弹幕数据过大" }
        val bytes = body.byteStream().readBytesBounded(DanmakuProtocol.MAX_BYTES)
        return String(bytes, Charsets.UTF_8)
    }
}
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "弹幕数据过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
