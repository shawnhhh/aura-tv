package tv.herrin.app

import java.net.URI

/** Manual form fields only. No pasted configuration inference or LAN discovery. */
internal object PersonalStoragePolicy {
    val mediaTypes = setOf("auto", "movie", "tv", "anime", "video")
    val extensions = setOf("mkv", "mp4", "ts", "m2ts", "mov", "m4v", "webm", "avi")
    fun endpoint(protocol: String, host: String, port: Int): String {
        require(protocol in setOf("https", "http", "smb"))
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" })
        require(port in 1..65535)
        return URI(protocol, null, host, port, "/", null, null).toASCIIString()
    }
    fun path(raw: String): String {
        require(raw.length <= 2048 && raw.none { it.code < 32 || it == '\\' || it == '?' || it == '#' })
        val parts = raw.split('/').filter(String::isNotEmpty)
        require(parts.none { it in setOf(".", "..") || it.contains('%') })
        return parts.joinToString("/")
    }
    fun contains(parent: String, child: String) = parent.isEmpty() || child == parent || child.startsWith("$parent/")
    data class Name(val title: String, val year: Int?, val season: Int?, val episode: Int?)
    fun parse(name: String): Name {
        val raw = name.substringBeforeLast('.')
        val episodic = Regex("(?i)(?:^|[ ._-])S(\\d{1,2})E(\\d{1,3})(?=$|[ ._-])").find(raw)
        val year = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(raw)?.value?.toIntOrNull()
        return Name(ArtworkTitle.searchQuery(raw.replace(episodic?.value.orEmpty().ifEmpty { "\u0000" }, " ")), year,
            episodic?.groupValues?.get(1)?.toIntOrNull(), episodic?.groupValues?.get(2)?.toIntOrNull())
    }
}
