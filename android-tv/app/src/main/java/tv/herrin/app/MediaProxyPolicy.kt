package tv.herrin.app

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/** Explicit, session-scoped authorization. No wildcard hosts or implicit CDN trust. */
class MediaProxyPolicy private constructor(
    internal val source: HttpUrl,
    internal val requestHeaders: Map<String, String>,
) {
    internal fun resolve(base: HttpUrl, reference: String): HttpUrl {
        require(reference.length in 1..8192 && reference.none { it <= ' ' || it == '\\' }) {
            "Invalid media reference"
        }
        val resolved = base.resolve(reference) ?: throw IllegalArgumentException("Invalid media reference")
        require(resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.fragment == null) {
            "Media reference credentials or fragment rejected"
        }
        validateLiteralHost(resolved.host)
        require(resolved.scheme == source.scheme && resolved.host == source.host && resolved.port == source.port) {
            "Media reference outside authorized origin"
        }
        return resolved
    }

    override fun toString(): String = "MediaProxyPolicy(authorized origin redacted)"

    companion object {
        /** Call only after the user has authorized this exact playback source and its headers. */
        fun authorize(sourceUrl: String, headers: Map<String, String> = emptyMap()): MediaProxyPolicy {
            require(sourceUrl.length in 1..8192 && sourceUrl.none { it <= ' ' || it == '\\' }) {
                "Invalid authorized media source"
            }
            val source = sourceUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Only HTTP media is supported")
            require(source.username.isEmpty() && source.password.isEmpty() && source.fragment == null) {
                "Source credentials or fragment rejected"
            }
            validateLiteralHost(source.host)
            val safe = LinkedHashMap<String, String>()
            headers.forEach { (name, value) ->
                val key = name.lowercase(java.util.Locale.ROOT)
                require(key in setOf("user-agent", "accept", "accept-language", "authorization", "cookie", "referer", "origin")) {
                    "Unauthorized request header"
                }
                require(key !in safe && value.length <= 8192 && value.all { it in ' '..'~' }) {
                    "Invalid authorized request header"
                }
                safe[key] = value
            }
            require(safe.values.sumOf { it.length } <= 16384) { "Request headers too large" }
            return MediaProxyPolicy(source, safe.toMap())
        }

        private fun validateLiteralHost(host: String) {
            // OkHttp can route numeric hosts without calling Dns.lookup.
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                require(isPublicAddress(InetAddress.getByName(host))) { "Media proxy destination rejected" }
            }
        }

        /** Conservative globally routable unicast check; reject every DNS answer if any is private. */
        internal fun isPublicAddress(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) {
                val a = b[0]; val c = b[1]
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && c in 64..127) || (a == 169 && c == 254) ||
                    (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
                    (a == 192 && c == 0) || (a == 192 && c == 2) ||
                    (a == 192 && c == 88 && b[2] == 99) ||
                    (a == 198 && c in 18..19) || (a == 198 && c == 51 && b[2] == 100) ||
                    (a == 203 && c == 0 && b[2] == 113))
            }
            // Only native global IPv6, excluding documentation, transition and special ranges.
            return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] < 2) &&
                !(b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0)
        }
    }
}

/** Validation happens in OkHttp's actual connection DNS path, with no second DNS lookup. */
internal class MediaProxyPublicDns(private val resolver: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !MediaProxyPolicy.isPublicAddress(it) }) {
            throw UnknownHostException("Media proxy destination rejected")
        }
        return addresses
    }
}
