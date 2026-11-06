package tv.herrin.app

/** Public TMDB size URLs are distinct cache keys. Never rewrite private image addresses. */
internal object ArtworkRequestPolicy {
    fun url(film: Film, hero: Boolean): String? {
        val source = if (hero) film.backdropUrl else
            if (film.metadataSource == "TMDB") film.backdropUrl ?: film.artworkUrl else film.artworkUrl
        if (source?.startsWith("https://image.tmdb.org/t/p/") != true) return source
        return source.replace(Regex("/t/p/(?:w[0-9]+|original)/"), "/t/p/${if (hero) "original" else if (source == film.backdropUrl) "w780" else "w500"}/")
    }
}
