package tv.herrin.app

/**
 * A user-triggered fallback must not silently select the same failed provider.
 * Stores only opaque per-film source IDs; it never stores URLs, HTTP headers or
 * user credentials. The actual sources must still be currently configured and
 * pass the normal consent / authorization gates on each new lookup.
 */
internal class HerrinTmdbFallbackIndex {
    private val current = linkedMapOf<String, String>()
    private val rejected = linkedMapOf<String, LinkedHashSet<String>>()

    @Synchronized fun select(filmId: String, sourceId: String) {
        require(filmId.startsWith("tmdb-") && sourceId.isNotBlank())
        current[filmId] = sourceId
    }

    @Synchronized fun selected(filmId: String): String? = current[filmId]

    @Synchronized fun rejected(filmId: String): Set<String> =
        rejected[filmId]?.toSet().orEmpty()

    /** Only a real selected source can be excluded; never loop on failed retry. */
    @Synchronized fun failSelected(filmId: String): Boolean {
        val selected = current.remove(filmId) ?: return false
        rejected.getOrPut(filmId) { linkedSetOf() }.add(selected)
        return true
    }

    @Synchronized fun reset(filmId: String) {
        current.remove(filmId)
        rejected.remove(filmId)
    }

    @Synchronized fun clear() {
        current.clear()
        rejected.clear()
    }
}
