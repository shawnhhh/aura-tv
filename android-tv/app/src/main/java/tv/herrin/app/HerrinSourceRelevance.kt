package tv.herrin.app

/** Relevance never rewrites a provider VOD, its origin or its parser configuration. */
internal enum class SourceRelevance(val label: String) {
    CONFIRMED("确认匹配"), POSSIBLE("可能相关"), OTHER("其他未确认结果")
}
internal object HerrinSourceRelevance {
    fun classify(target: HerrinFilmIdentity, offer: HerrinSourceMovie): SourceRelevance {
        val wanted = target.names.map(ArtworkTitle::normalize).filter { it.isNotBlank() }
        val names = (listOf(offer.film.title) + offer.titleAliases).map(ArtworkTitle::normalize).filter { it.isNotBlank() }
        val exact = wanted.any { it in names }
        if (exact) return if (HerrinFilmIdentityMatch.matches(target, offer))
            SourceRelevance.CONFIRMED else SourceRelevance.POSSIBLE
        // Series/subtitle differences are manual choices, never automatic matches.
        // Require enough shared title and at least half of the longer name.
        val related = wanted.any { a -> names.any { b ->
            val short = if(a.length < b.length) a else b
            val long = if(a.length < b.length) b else a
            short.length >= 3 && short.length * 2 >= long.length && long.contains(short)
        } }
        return if (related) SourceRelevance.POSSIBLE else SourceRelevance.OTHER
    }
}
