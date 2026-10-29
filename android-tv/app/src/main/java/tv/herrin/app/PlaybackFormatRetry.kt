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
    const val MP4 = "video/mp4"

    /** Only supported formats from a trusted source result can override URL sniffing. */
    fun declaredMime(value: String?): String? = when (value?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "hls", "m3u8" -> HLS
        "application/dash+xml", "dash", "mpd" -> DASH
        "video/mp4", "mp4" -> MP4
        else -> null
    }

    /** The format declared for a primary URL must not leak onto unrelated backup URLs. */
    fun candidateMime(uri: String, candidateIndex: Int, primaryDeclaredMime: String?): String? =
        if (candidateIndex == 0) declaredMime(primaryDeclaredMime) ?: initialMime(uri)
        else initialMime(uri)

    fun initialMime(uri: String): String? {
        val withoutFragment = uri.substringBefore('#').lowercase(java.util.Locale.ROOT)
        val path = withoutFragment.substringBefore('?')
        return when {
            path.endsWith(".m3u8") -> HLS
            path.endsWith(".mpd") -> DASH
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

    fun retryNextSource(errorCode: Int, httpStatus: Int? = null): Boolean =
        errorCode in 2000..4999 && errorCode !in setOf(2006, 2007) && httpStatus != 429

    data class Attempt(val candidateIndex: Int, val uri: String, val mime: String?)

    /** One monotonic, bounded retry budget per user-selected playback session. */
    class Session(primary: String, alternates: List<String>, private val primaryMime: String?) {
        val candidates: List<String> = (listOf(primary) + alternates).distinct().take(8)
        var current: Attempt = attempt(0)
            private set
        private var exhausted = false

        private fun attempt(index: Int) = Attempt(index, candidates[index],
            candidateMime(candidates[index], index, primaryMime))

        /** A deliberate user retry starts a fresh bounded traversal. */
        fun restart(): Attempt {
            current = attempt(0)
            exhausted = false
            return current
        }

        fun next(errorCode: Int, httpStatus: Int?): Attempt? {
            if (exhausted) return null
            // An HTTP response is stronger evidence than a parser code. In particular,
            // do not retry the same URL with different MIME types after an HTTP denial.
            val mime = if (httpStatus == null) nextMime(current.uri, current.mime, errorCode) else null
            if (mime != null) {
                current = current.copy(mime = mime)
                return current
            }
            if (retryNextSource(errorCode, httpStatus) && current.candidateIndex < candidates.lastIndex) {
                current = attempt(current.candidateIndex + 1)
                return current
            }
            exhausted = true
            return null
        }
    }
}
