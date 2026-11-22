package tv.herrin.app

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Manual form fields only. No pasted configuration inference or LAN discovery. */
internal object PersonalStoragePolicy {
    private val encodedTraversal = Regex("(?i)%(?:2e|2f|5c|25)")
    private val episodePattern = Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})E(\\d{1,3})(?![0-9])")
    private val yearPattern = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    val mediaTypes = setOf("auto", "movie", "tv", "anime", "video")
    val extensions = setOf("mkv", "mp4", "ts", "m2ts", "mov", "m4v", "webm", "avi")
    fun host(raw: String): String {
        val value = raw.trim().removeSurrounding("[", "]")
        require(value.isNotBlank() && value.length <= 253 && value.none { it.isWhitespace() || it in "/\\@?#" })
        return if(':' in value) value.lowercase() else java.net.IDN.toASCII(value, java.net.IDN.USE_STD3_ASCII_RULES).lowercase()
    }
    data class WebDavAddress(val scheme: String, val host: String, val port: Int, val basePath: String)
    fun webDavAddress(raw: String, portField: String = ""): WebDavAddress {
        require(raw.length in 1..2048)
        require(raw.trim().startsWith("http://", true) || raw.trim().startsWith("https://", true))
        // HttpUrl canonicalizes dot segments. Reject traversal before that
        // normalization so a pasted URL cannot silently change its subtree.
        path(raw.trim().substringAfter("://").substringAfter('/', ""))
        val url = requireNotNull(raw.trim().toHttpUrlOrNull())
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        val explicitPort = Regex(":([0-9]+)$").find(raw.trim().substringAfter("://").substringBefore('/'))
            ?.groupValues?.get(1)?.toIntOrNull()
        val supplied = portField.trim().takeIf(String::isNotEmpty)?.let { requireNotNull(it.toIntOrNull()) }
        require(supplied == null || explicitPort == null || supplied == explicitPort) { "WEBDAV_PORT_CONFLICT" }
        val port = supplied ?: url.port
        endpoint(url.scheme, url.host, port)
        return WebDavAddress(url.scheme, url.host, port, path(URI(url.toString()).path.orEmpty()))
    }
    fun endpoint(protocol: String, host: String, port: Int): String {
        require(protocol in setOf("https", "http", "smb"))
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" })
        require(port in 1..65535)
        return URI(protocol, null, PersonalStoragePolicy.host(host), port, "/", null, null).toASCIIString()
    }
    fun sameOrigin(a: URI, b: URI): Boolean {
        fun port(u: URI) = if(u.port >= 0) u.port else when(u.scheme?.lowercase()) { "https" -> 443; "http" -> 80; else -> -1 }
        return a.host != null && b.host != null && a.rawUserInfo == null && b.rawUserInfo == null &&
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }
    fun path(raw: String): String {
        require(raw.length <= 2048 && raw.none { it.code < 32 || it == '\\' || it == '?' || it == '#' })
        val parts = raw.split('/').filter(String::isNotEmpty)
        require(parts.none { it in setOf(".", "..") || encodedTraversal.containsMatchIn(it) })
        return parts.joinToString("/")
    }
    fun contains(parent: String, child: String) = parent.isEmpty() || child == parent || child.startsWith("$parent/")
    data class Name(val title: String, val year: Int?, val season: Int?, val episode: Int?)
    private val numbers = "零〇一二两兩三四五六七八九十百"
    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0; var digit = 0
        for (c in raw) when (c) {
            '十', '百' -> { total += (if (digit == 0) 1 else digit) * if(c == '十') 10 else 100; digit = 0 }
            else -> digit = digits[c] ?: return null
        }
        return (total + digit).takeIf { it > 0 }
    }
    fun parse(name: String, parentPath: String = ""): Name {
        // Normalize the lookup claim only. The index keeps the untouched remote path/name.
        val raw = java.text.Normalizer.normalize(name.substringBeforeLast('.'), java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\p{Cf}"), "")
        val episodic = episodePattern.find(raw)
        val chineseEpisode = Regex("第\\s*([0-9$numbers]{1,6})\\s*[集话話]").find(raw)
        val bracketEpisode = Regex("(?i)(?:\\[| - )(\\d{2,3})(?:v\\d)?(?=]|[ ._-]|$)").find(raw)
        val seasonFolder = Regex("(?i)^(?:(?:season[ ._-]*|S)(\\d{1,2})|第([0-9$numbers]{1,4})季)$")
        val parents = parentPath.split('/').filter(String::isNotBlank)
        val parent = parents.asReversed().firstOrNull { !seasonFolder.matches(it) }.orEmpty()
        val parentSeason = parents.asReversed().firstNotNullOfOrNull { part -> seasonFolder.matchEntire(part)?.let {
            number(it.groupValues[1].ifBlank { it.groupValues[2] })
        } }
        val claim = episodic?.groupValues?.get(2)?.toIntOrNull() ?: chineseEpisode?.groupValues?.get(1)?.let(::number)
            ?: bracketEpisode?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 && it !in setOf(480,576,720) && parent.isNotBlank() }
        var title = raw
        listOfNotNull(episodic, chineseEpisode, bracketEpisode.takeIf { claim != null && episodic == null && chineseEpisode == null })
            .sortedByDescending { it.range.first }.forEach { title = title.replaceRange(it.range, " ") }
        // A release group preceding a clearly episodic anime title is not part of the work name.
        if (claim != null) title = title.replace(Regex("^\\[[^]]{1,80}]\\s*"), " ")
        title = title.replace(Regex("(?i)\\[[^]]*(?:字幕|sub|rip|web|x26|hevc|4k|1080|2160|[A-F0-9]{8})[^]]*]"), " ")
        title = title.replace(Regex("(?i)\\b(?:bluray|remux|uhd|proper|repack|10bit|8bit|ddp?[ .]?\\d(?:[.]\\d)?|flac|truehd)\\b"), " ")
        title = ArtworkTitle.searchQuery(title).trim(' ', '-', '[', ']', '(', ')')
        if ((parent.isNotBlank() && (episodic?.range?.first == 0 || chineseEpisode?.range?.first == 0)) ||
            title.isBlank() || title.matches(Regex("(?i)(?:ep?[ ._-]*)?\\d{1,3}"))) title = ArtworkTitle.searchQuery(parent)
        fun releaseYear(value: String) = yearPattern.findAll(value).lastOrNull {
            it.range.first > 0 && value.take(it.range.first).any(Char::isLetter)
        }?.value?.toIntOrNull()
        val year = releaseYear(raw) ?: releaseYear(parent)
        return Name(title.ifBlank { raw }, year,
            episodic?.groupValues?.get(1)?.toIntOrNull() ?: parentSeason ?: if (claim != null) 1 else null, claim)
    }
}
