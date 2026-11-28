package tv.herrin.app

/** Reuse the TMDB parser's original-title projection and existing exact identity rules. */
internal object PersonalMediaMatching {
    fun canonical(raw: String): String {
        val value = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\p{Cf}"), "")
        return if (android.os.Build.VERSION.SDK_INT >= 29) Simplified.convert(value) else value
    }
    @androidx.annotation.RequiresApi(29)
    private object Simplified {
        private val converter by lazy { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }
        @Synchronized fun convert(value: String): String = converter.transliterate(value)
    }

    fun choose(expected: HerrinFilmIdentity, films: List<Film>, discovery: HerrinTmdbDiscovery): Film? =
        films.filter { film ->
            val identity = discovery.identity(film) ?: return@filter false
            if (expected.year != null && film.year.toIntOrNull() != expected.year) return@filter false
            val aliases = listOfNotNull(film.originalDisplay?.title,
                film.subtitle.takeIf { film.metadataSource == "TMDB" && it.isNotBlank() })
            HerrinFilmIdentityMatch.matches(expected.copy(names = expected.names.map(::canonical)),
                HerrinSourceMovie("tmdb", HerrinSourceType.NAS, film.copy(title = canonical(film.title)), identity.first, titleAliases = aliases.map(::canonical)))
        }.distinctBy(Film::id).singleOrNull()

    /** Auto-imported files without a season/episode hint are not evidence of a movie.
     * Search multi includes films and shows: accept only one unambiguous exact identity
     * across BOTH types, never silently prefer a same-title film over a series.
     */
    fun chooseUnclassified(expected: HerrinFilmIdentity, films: List<Film>, discovery: HerrinTmdbDiscovery): Film? =
        listOfNotNull(
            choose(expected.copy(mediaType = "movie"), films, discovery),
            choose(expected.copy(mediaType = "tv"), films, discovery)
        ).singleOrNull()
    data class Decision(val film: Film?, val ambiguous: Boolean = false)

    /** Exact normalized and original names outrank minor Latin typos. Fuzzy
     * claims require a year, known type and agreement on every title digit. */
    fun decide(names: List<String>, year: Int?, type: String?, candidates: List<Film>,
               discovery: HerrinTmdbDiscovery, allowApproximate: Boolean = true): Decision {
        val expected = names.map { ArtworkTitle.normalize(canonical(it)) }.filter { it.isNotEmpty() }.distinct()
        val byIdentity = candidates.groupBy(discovery::identity).filterKeys { it != null && (type == null || it.first == type) }
        val exact = byIdentity.mapNotNull { (_, entries) ->
            val known = entries.firstOrNull { it.year.isNotBlank() } ?: entries.first()
            if (year != null && known.year.toIntOrNull() != year) return@mapNotNull null
            known.takeIf { entries.any { candidate -> filmNames(candidate).any { it in expected } } }
        }
        if (exact.isNotEmpty()) return Decision(exact.singleOrNull(), exact.size > 1)
        if (!allowApproximate || year == null || type == null) return Decision(null)
        val approximate = byIdentity.mapNotNull { (_, entries) ->
            val dated = entries.firstOrNull { it.year.toIntOrNull() == year } ?: return@mapNotNull null
            dated.takeIf { entries.any { entry ->
                filmNames(entry).any { actual -> expected.any { oneLatinEdit(it, actual) } }
            } }
        }
        return Decision(approximate.singleOrNull(), approximate.size > 1)
    }

    private fun filmNames(film: Film) = listOfNotNull(film.title, film.originalDisplay?.title,
        film.subtitle.takeIf { film.metadataSource == "TMDB" && it.isNotBlank() })
        .map { ArtworkTitle.normalize(canonical(it)) }

    private fun oneLatinEdit(a: String, b: String): Boolean {
        if (minOf(a.length, b.length) < 8 || kotlin.math.abs(a.length - b.length) > 1 ||
            !a.all { it in 'a'..'z' || it in '0'..'9' } || !b.all { it in 'a'..'z' || it in '0'..'9' } ||
            a.filter(Char::isDigit) != b.filter(Char::isDigit)) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        val first = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: return a.length != b.length
        return if (a.length == b.length) shorter.substring(first + 1) == longer.substring(first + 1)
            else shorter.substring(first) == longer.substring(first + 1)
    }
}
