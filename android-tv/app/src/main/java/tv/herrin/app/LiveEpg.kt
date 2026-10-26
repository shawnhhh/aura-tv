package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class LiveProgramme(val channelId: String, val title: String, val startMs: Long, val stopMs: Long)

sealed interface LiveEpgResult {
    data class Ready(val programmes: List<LiveProgramme>) : LiveEpgResult
    data class Error(val reason: String) : LiveEpgResult
}

/** Bounded XMLTV parser. External entities and DTD fetching are never enabled. */
internal object AuthorizedXmlTv {
    private const val MAX_BYTES = 2_000_000
    private const val MAX_PROGRAMMES = 800
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
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val xml = factory.newDocumentBuilder().parse(InputSource(StringReader(document)))
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
                setRequestProperty("Accept", "application/xml, text/xml")
            }
            connection = active
            if (active.responseCode != 200) throw IOException("EPG unavailable")
            val type = active.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && type !in setOf("application/xml", "text/xml", "application/octet-stream"))
                throw IOException("Unsupported XMLTV response")
            val bytes = active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Too large")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            LiveEpgResult.Ready(AuthorizedXmlTv.decode(bytes.toString(Charsets.UTF_8)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LiveEpgResult.Error("节目单读取失败；直播频道仍可播放")
        } finally {
            connection?.disconnect()
        }
    }
}
