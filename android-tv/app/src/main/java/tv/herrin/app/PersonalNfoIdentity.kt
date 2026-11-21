package tv.herrin.app

/** Bounded local sidecar hints; no entity expansion, external URLs or guessed IDs. */
internal object PersonalNfoIdentity {
    data class Hint(val type: String, val tmdb: Int)
    fun parse(xml: String): Hint? {
        if (xml.length > 65536 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return null
        return runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            val root = document.documentElement
            // Episode TMDB IDs identify an episode, not its parent TV series.
            val type = when(root.tagName.lowercase()) { "movie" -> "movie"; "tvshow" -> "tv"; else -> return null }
            val ids = mutableSetOf<Int>()
            val unique = root.getElementsByTagName("uniqueid")
            for(i in 0 until unique.length) {
                val element = unique.item(i) as org.w3c.dom.Element
                if(element.getAttribute("type").equals("tmdb", true)) element.textContent.trim().toIntOrNull()?.let(ids::add)
            }
            val legacy = root.getElementsByTagName("tmdbid")
            for(i in 0 until legacy.length) legacy.item(i).textContent.trim().toIntOrNull()?.let(ids::add)
            ids.singleOrNull()?.takeIf { it > 0 }?.let { Hint(type,it) }
        }.getOrNull()
    }
}
