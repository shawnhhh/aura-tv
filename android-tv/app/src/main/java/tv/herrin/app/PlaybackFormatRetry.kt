package tv.herrin.app

/**
 * ExoPlayer infers a progressive stream when an HLS/DASH endpoint omits its
 * extension. A parsing error alone is not evidence of a dead playback URL:
 * retry the very same authorized URL with an explicit manifest MIME type.
 *
 * This is deliberately not a web parser or a stream discovery service.
 */
internal object PlaybackFormatRetry {
    const val HLS = "application/x-mpegURL"
    const val DASH = "application/dash+xml"

    /** Only supported manifest formats from a trusted source result can override URL sniffing. */
    fun declaredMime(value: String?): String? = when (value?.trim()?.lowercase()?.substringBefore(';')) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "hls", "m3u8" -> HLS
        "application/dash+xml", "dash", "mpd" -> DASH
        else -> null
    }

    fun initialMime(uri: String): String? {
        val withoutFragment = uri.substringBefore('#').lowercase()
        val path = withoutFragment.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || withoutFragment.contains(".m3u8") -> HLS
            path.endsWith(".mpd") || withoutFragment.contains(".mpd") -> DASH
            else -> null
        }
    }

    fun nextMime(uri: String, current: String?, errorCode: Int): String? {
        // Media3 3000–3999 denote parsing/content errors. Trying HLS/DASH for
        // HTTP 403 or a decoder failure would mask the actual root cause.
        if (errorCode !in 3000..3999 || initialMime(uri) != null) return null
        return when (current) {
            null -> HLS
            HLS -> DASH
            else -> null
        }
    }

    fun retryNextSource(errorCode: Int): Boolean = errorCode in 2000..4999
}
