package tv.herrin.app

import java.net.URI

/**
 * The FongMi Spider may return an HLS master/segment playlist whose nested
 * media and AES key links still target TVBox's fixed 9978 proxy or proxy://.
 * Rebind only known CatVod routes to this JAR's authenticated local session.
 * Unrecognized localhost paths make the manifest unusable, never forward them.
 */
internal object HerrinSpiderHlsProxyRewrite {
    const val MAX_MANIFEST_BYTES = 256 * 1024
    private val uriAttribute = Regex("""URI="([^"\r\n]{1,4096})"""")

    fun rewrite(text: String, siteKey: String, proxy: SpiderLoopbackProxy): String? {
        if (!text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U"))
            return null
        var invalid = false
        fun translate(raw: String): String {
            val candidate = raw.trim()
            val needs = candidate.startsWith("proxy://", true) ||
                runCatching {
                    val uri = URI(candidate)
                    uri.scheme == "http" && uri.host?.lowercase() in
                        setOf("127.0.0.1", "localhost", "::1", "[::1]")
                }.getOrDefault(false)
            if (!needs) return raw
            val mapped = when {
                candidate.startsWith("proxy://", true) ->
                    SpiderProxyPlayback.rebaseUrl(candidate, siteKey, proxy)
                else -> {
                    val uri = runCatching { URI(candidate) }.getOrNull()
                    if (uri?.path == "/proxy")
                        SpiderProxyPlayback.rebaseUrl(candidate, siteKey, proxy)
                    else null
                }
            }
            if (mapped == null) {
                invalid = true
                return raw
            }
            val uri = runCatching { URI(mapped) }.getOrNull()
            if (uri?.port != proxy.port ||
                !uri.rawQuery.orEmpty().contains("session=" + proxy.token)) {
                invalid = true
                return raw
            }
            return mapped
        }
        val transformed = text.split('\n').map { line ->
            val body = line.trimEnd('\r')
            if (body.startsWith("#")) {
                uriAttribute.replace(body) { match ->
                    "URI=\"" + translate(match.groupValues[1]) + "\""
                }
            } else if (body.isNotBlank()) translate(body) else body
        }.joinToString("\n")
        return if (invalid) null else transformed
    }
}
