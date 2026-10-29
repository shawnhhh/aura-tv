package tv.herrin.app

/** Bounded plain-text rendering of provider-supplied HTML fragments. */
internal object SourceText {
    fun synopsis(raw: String): String = raw
        .replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&", ignoreCase = true)
        .replace(Regex("&(?:nbsp|#160|#xA0);", RegexOption.IGNORE_CASE), " ")
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace(Regex("\\s+"), " ").trim().take(1200)
}
