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
}
