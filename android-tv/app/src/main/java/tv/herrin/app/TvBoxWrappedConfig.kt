package tv.herrin.app

import java.util.Base64

/** TVBox config can be appended to an otherwise ordinary JPEG as `marker**base64(JSON)`. */
internal object TvBoxWrappedConfig {
    private const val MAX_CONFIG_BYTES = 1_048_576
    private const val MAX_ENCODED_BYTES = MAX_CONFIG_BYTES * 4 / 3 + 8

    fun extract(bytes: ByteArray): String? {
        if (bytes.size < 16 || bytes.size > MAX_CONFIG_BYTES ||
            bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        // A complete JPEG is required; arbitrary image metadata must never be treated as scripts.
        var end = -1
        for (i in 2 until bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) end = i + 2
        }
        if (end < 0 || end + 12 >= bytes.size) return null
        val trailer = bytes.copyOfRange(end, bytes.size).toString(Charsets.US_ASCII)
        val divider = trailer.indexOf("**")
        if (divider !in 4..64) return null
        val prefix = trailer.substring(0, divider)
        if (!prefix.all { it.isLetterOrDigit() }) return null
        val encoded = trailer.substring(divider + 2).trim()
        if (encoded.length !in 16..MAX_ENCODED_BYTES || encoded.any {
            !it.isLetterOrDigit() && it !in "+/="
        }) return null
        val decoded = try { Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { return null }
        if (decoded.size !in 2..MAX_CONFIG_BYTES) return null
        val text = decoded.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return text.takeIf { it.startsWith("{") }
    }

    /** Ignore JavaScript-style comments only when outside a quoted JSON string. */
    fun stripComments(raw: String): String {
        val output = StringBuilder(raw.length)
        var inString = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val next = raw.getOrNull(i + 1)
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false
                    output.append(c)
                }
            } else if (blockComment) {
                if (c == '\n' || c == '\r') output.append(c)
                if (c == '*' && next == '/') {
                    blockComment = false
                    i++
                }
            } else if (inString) {
                output.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when {
                c == '"' -> { inString = true; output.append(c) }
                c == '/' && next == '/' -> { lineComment = true; i++ }
                c == '/' && next == '*' -> { blockComment = true; i++ }
                else -> output.append(c)
            }
            i++
        }
        return output.toString()
    }
}
