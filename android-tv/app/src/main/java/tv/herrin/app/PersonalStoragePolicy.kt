package tv.herrin.app

import java.net.URI

/** Manual form fields only. No pasted configuration inference or LAN discovery. */
internal object PersonalStoragePolicy {
    private val encodedTraversal = Regex("(?i)%(?:2e|2f|5c|25)")
    private val episodePattern = Regex("(?i)(?:^|[ ._-])S(\\d{1,2})E(\\d{1,3})(?=$|[ ._-])")
    private val yearPattern = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    val mediaTypes = setOf("auto", "movie", "tv", "anime", "video")
    val extensions = setOf("mkv", "mp4", "ts", "m2ts", "mov", "m4v", "webm", "avi")
    fun endpoint(protocol: String, host: String, port: Int): String {
        require(protocol in setOf("https", "http", "smb"))
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" })
        require(port in 1..65535)
        return URI(protocol, null, host, port, "/", null, null).toASCIIString()
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
    fun parse(name: String): Name {
        val raw = name.substringBeforeLast('.')
        val episodic = episodePattern.find(raw)
        val year = yearPattern.find(raw)?.value?.toIntOrNull()
        return Name(ArtworkTitle.searchQuery(raw.replace(episodic?.value.orEmpty().ifEmpty { "\u0000" }, " ")), year,
            episodic?.groupValues?.get(1)?.toIntOrNull(), episodic?.groupValues?.get(2)?.toIntOrNull())
    }
}
