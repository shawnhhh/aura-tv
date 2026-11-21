package tv.herrin.app

/** Bounded local sidecar hints; no entity expansion, external URLs or guessed IDs. */
internal object PersonalNfoIdentity {
    data class Hint(val type: String, val tmdb: Int)
    fun parse(xml: String): Hint? {
        if (xml.length > 65536 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return null
        val type = when {
            Regex("<movie(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(xml) -> "movie"
            Regex("<(?:tvshow|episodedetails)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(xml) -> "tv"
            else -> return null
        }
        val id = Regex("""<uniqueid\s+[^>]*type=["']tmdb["'][^>]*>\s*([0-9]+)\s*</uniqueid>""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("<tmdbid>\\s*([0-9]+)\\s*</tmdbid>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.toIntOrNull()
        return id?.takeIf { it > 0 }?.let { Hint(type, it) }
    }
}
