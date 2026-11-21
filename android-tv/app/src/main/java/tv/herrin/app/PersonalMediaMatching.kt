package tv.herrin.app

/** Reuse the TMDB parser's original-title projection and existing exact identity rules. */
internal object PersonalMediaMatching {
    fun choose(expected: HerrinFilmIdentity, films: List<Film>, discovery: HerrinTmdbDiscovery): Film? =
        films.filter { film ->
            val identity = discovery.identity(film) ?: return@filter false
            val aliases = listOfNotNull(film.originalDisplay?.title,
                film.subtitle.takeIf { film.metadataSource == "TMDB" && it.isNotBlank() })
            HerrinFilmIdentityMatch.matches(expected,
                HerrinSourceMovie("tmdb", HerrinSourceType.NAS, film, identity.first, titleAliases = aliases))
        }.distinctBy(Film::id).singleOrNull()
}
