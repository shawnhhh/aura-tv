package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.GZIPInputStream

data class LiveProgramme(val channelId: String, val title: String, val startMs: Long, val stopMs: Long)
data class LiveGuideSnapshot(
    val now: LiveProgramme?,
    val next: LiveProgramme?,
    val progress: Float?
)

sealed interface LiveEpgResult {
    data class Ready(val programmes: List<LiveProgramme>) : LiveEpgResult
    data class Error(val reason: String) : LiveEpgResult
}

/** Bounded XMLTV parser. External entities and DTD fetching are never enabled. */
internal object AuthorizedXmlTv {
    // A bounded, moderately sized schedule supports compressed day guides
    // without allowing large documents to exhaust a TV's heap.
    internal const val MAX_BYTES = 6_000_000
    private const val MAX_PROGRAMMES = 8_000
    private val simpleDtd = Regex("""<!DOCTYPE\s+tv\s+SYSTEM\s+["'][^"'<>]+["']\s*>""",
        RegexOption.IGNORE_CASE)
    private val formatters = listOf(
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyyMMddHHmm Z", Locale.ROOT)
    )

    fun decode(raw: String): List<LiveProgramme> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(!raw.contains("<!ENTITY", ignoreCase = true))
        val document = raw.replace(simpleDtd, "")
        require(!document.contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Android's Harmony parser (including API 30) does not implement all
            // JAXP feature URIs. Keep the up-front DOCTYPE/ENTITY rejection and
            // the fail-closed resolver below even when a feature is unsupported.
            listOf(
                "http://javax.xml.XMLConstants/feature/secure-processing" to true,
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
            ).forEach { (feature, enabled) ->
                runCatching { setFeature(feature, enabled) }
            }
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw SAXException("External XML entities are forbidden")
            }
        }
        val xml = builder.parse(InputSource(StringReader(document)))
        require(xml.documentElement?.nodeName == "tv")
        val nodes = xml.getElementsByTagName("programme")
        require(nodes.length in 1..MAX_PROGRAMMES)
        val programmes = ArrayList<LiveProgramme>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val channel = node.getAttribute("channel").trim()
            val start = parseTime(node.getAttribute("start")) ?: continue
            val stop = parseTime(node.getAttribute("stop")) ?: continue
            val title = node.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
            if (channel.length !in 1..80 || title.length !in 1..120 ||
                title.any { it.code < 32 } || stop <= start) continue
            programmes += LiveProgramme(channel, title, start, stop)
        }
        require(programmes.isNotEmpty())
        return programmes
    }

    private fun parseTime(value: String): Long? = formatters.firstNotNullOfOrNull { formatter ->
        runCatching { OffsetDateTime.parse(value.trim(), formatter).toInstant().toEpochMilli() }.getOrNull()
    }

    fun current(programmes: List<LiveProgramme>, channelId: String, nowMs: Long): LiveProgramme? =
        programmes.firstOrNull { it.channelId == channelId && nowMs in it.startMs until it.stopMs }

    /** Local-only programme lookup; no extra network request when TV focus moves. */
    fun snapshots(programmes: List<LiveProgramme>, nowMs: Long): Map<String, LiveGuideSnapshot> =
        programmes.groupBy { it.channelId }.mapValues { (_, entries) ->
            val current = entries.filter { nowMs in it.startMs until it.stopMs }
                .maxWithOrNull(compareBy<LiveProgramme> { it.startMs }.thenBy { it.stopMs })
            val nextBoundary = current?.stopMs ?: nowMs
            val next = entries.asSequence().filter { it.startMs >= nextBoundary }
                .minWithOrNull(compareBy<LiveProgramme> { it.startMs }.thenBy { it.stopMs })
            val progress = current?.let {
                ((nowMs - it.startMs).toDouble() / (it.stopMs - it.startMs))
                    .coerceIn(0.0, 1.0).toFloat()
            }
            LiveGuideSnapshot(current, next, progress)
        }
}

/** Read a user-supplied .xml or .xml.gz without trusting MIME or filename.
 * Both the transferred bytes and inflated XML are capped (gzip-bomb safe).
 */
internal object XmlTvPayload {
    private const val MAX_TRANSFER_BYTES = 4_000_000

    fun read(input: InputStream): String {
        val transfer = input.use { readBounded(it, MAX_TRANSFER_BYTES) }
        val gzip = transfer.size >= 2 &&
            transfer[0] == 0x1f.toByte() && transfer[1] == 0x8b.toByte()
        val document = if (gzip) {
            GZIPInputStream(ByteArrayInputStream(transfer)).use {
                readBounded(it, AuthorizedXmlTv.MAX_BYTES)
            }
        } else transfer
        return document.toString(Charsets.UTF_8)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("XMLTV payload too large")
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }
}

/** Optional, explicitly configured HTTPS XMLTV URL; no automatic guide discovery. */
class RemoteLiveEpgRepository(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val timeoutMs: Int = 9_000
) {
    suspend fun load(rawUrl: String): LiveEpgResult = withContext(Dispatchers.IO) {
        val url = AuthorizedUrls.https(rawUrl)
            ?: return@withContext LiveEpgResult.Error("请输入合法的 HTTPS XMLTV 地址")
        var connection: HttpURLConnection? = null
        try {
            val active = openConnection(url).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/xml, text/xml, application/gzip, application/x-gzip")
            }
            connection = active
            if (active.responseCode != 200) throw IOException("EPG unavailable")
            val type = active.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && type !in setOf("application/xml", "text/xml",
                    "application/octet-stream", "application/gzip", "application/x-gzip"))
                throw IOException("Unsupported XMLTV response")
            LiveEpgResult.Ready(AuthorizedXmlTv.decode(XmlTvPayload.read(active.inputStream)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LiveEpgResult.Error("节目单读取失败；直播频道仍可播放")
        } finally {
            connection?.disconnect()
        }
    }
}
