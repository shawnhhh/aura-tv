package tv.herrin.app

import java.security.MessageDigest

/**
 * A view over the active *single* catalog. Group IDs are never playback IDs:
 * every playable alternative below retains its original Film / episode / line.
 */
internal data class ShowMember(val film: Film, val seasonHint: Int?, val baseTitle: String)

internal data class ShowGroup(
    val key: String,
    val title: String,
    val members: List<ShowMember>,
    val tmdbTvId: Int?
) {
    /** Stable detail-fetch effect identity: changes only when source members change. */
    val memberIds: List<String> get() = members.map { it.film.id }.sorted()

    val representative: Film get() = members.sortedWith(
        compareByDescending<ShowMember> { it.film.metadataSource == "TMDB" }
            .thenByDescending { !it.film.backdropUrl.isNullOrBlank() }
            .thenByDescending { !it.film.artworkUrl.isNullOrBlank() }
            .thenBy { it.seasonHint ?: Int.MAX_VALUE }
            .thenBy { it.film.title }
            .thenBy { it.film.id }
    ).first().film

    val displayFilm: Film get() = representative.copy(title = title)

    fun memberFor(filmId: String): ShowMember? = members.firstOrNull { it.film.id == filmId }
}

internal class ShowCatalog private constructor(
    val groups: List<ShowGroup>,
    private val original: List<Film>
) {
    private val byMember = groups.flatMap { group ->
        group.members.map { it.film.id to group }
    }.toMap()

    fun groupFor(filmId: String): ShowGroup? = byMember[filmId]

    /** Keep first occurrence in the caller's order, but use one canonical show card. */
    fun collapse(films: List<Film>): List<Film> {
        val seen = mutableSetOf<String>()
        return films.mapNotNull { film ->
            val group = byMember[film.id]
            val identity = group?.key ?: "film:${film.id}"
            if (!seen.add(identity)) null else group?.displayFilm ?: film
        }
    }

    /** Search raw members first; a query for Season 3 still finds its Season 1 cover. */
    fun search(query: String, category: String, newestFirst: Boolean): List<Film> =
        collapse(SearchDiscovery.results(original, query, category, newestFirst))

    fun favorites(ids: Set<String>): List<Film> = collapse(original.filter { it.id in ids })

    fun watchProgress(film: Film, load: (Film, PlaybackEpisode, PlaybackLine) -> WatchPosition?): WatchPosition? {
        val members = byMember[film.id]?.members?.map { it.film } ?: listOf(film)
        return members.asSequence().flatMap { source -> source.playbackEpisodes.asSequence().flatMap { ep ->
            ep.lines.asSequence().mapNotNull { line -> load(source, ep, line) }
        } }.maxByOrNull { it.updatedAtMs }
    }

    companion object {
        fun build(films: List<Film>, catalogScope: String): ShowCatalog =
            ShowCatalog(ShowGroupMatcher.groups(films, catalogScope), films)
    }
}

internal object ShowGroupMatcher {
    private data class Candidate(val member: ShowMember, val family: String, val type: String,
                                 val explicitSeasons: Set<Int>)

    // Accept an optional parenthesized release year after a verified season suffix.
    // Keep free-text quality/edition suffixes out: uncertain aliases stay separate.
    private val chineseSeason = Regex("^(.+?)[\\s·._—-]*[（(]?第\\s*([0-9一二三四五六七八九十零〇两]{1,3})\\s*季[）)]?(?:\\s*[（(](?:19|20)\\d{2}[）)])?\\s*$", RegexOption.IGNORE_CASE)
    private val englishSeason = Regex("^(.+?)[\\s·._—-]+[（(]?(?:Season\\s*|S)(\\d{1,2})[）)]?(?:\\s*[（(](?:19|20)\\d{2}[）)])?\\s*$", RegexOption.IGNORE_CASE)
    private val unsafeParts = Regex("(?:剧场版|特别篇|特别版|番外|上篇|下篇|\\b(?:OVA|OAD|SP|SPECIAL|Part\\s*[12])\\b)", RegexOption.IGNORE_CASE)

    private val chineseDigits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2,
        '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7,
        '八' to 8, '九' to 9)

    internal fun seasonNumber(text: String): Int? {
        text.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
        if (text == "十") return 10
        val marker = text.indexOf('十')
        if (marker >= 0) {
            if (text.indexOf('十', marker + 1) >= 0 || marker > 1 || text.length - marker - 1 > 1)
                return null
            val tens = if (marker == 0) 1 else chineseDigits[text[0]] ?: return null
            val units = if (marker == text.lastIndex) 0
                else chineseDigits[text[marker + 1]] ?: return null
            return (tens * 10 + units).takeIf { it in 1..99 }
        }
        if (text.length == 1) return chineseDigits[text[0]]?.takeIf { it in 1..9 }
        return null
    }

    internal fun titleParts(title: String): Pair<String, Int?> {
        val value = title.trim()
        for (pattern in listOf(chineseSeason, englishSeason)) {
            val match = pattern.matchEntire(value) ?: continue
            val number = seasonNumber(match.groupValues[2]) ?: continue
            val base = match.groupValues[1].trim(' ', '·', '.', '-', '_', '—')
            if (base.isNotBlank()) return base to number
        }
        return value to null
    }

    private fun family(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    private fun kind(category: String): String = when (category) {
        "剧集", "动漫" -> "series"
        "综艺" -> "variety"
        "纪录片" -> "documentary"
        else -> ""
    }

    private fun availableSeasons(film: Film): Set<Int> = film.playbackEpisodes.flatMap { episode ->
        listOfNotNull(SeasonEpisodes.explicitEpisode(episode.title)?.first) +
            episode.lines.mapNotNull { SeasonEpisodes.explicitSeason(it.title) }
    }.toSet()

    private fun candidate(film: Film): Candidate? {
        val kind = kind(film.category)
        // Metadata is display-only: its official show title often omits the source season.
        // The saved source label also retains special/OVA markers that must prevent grouping.
        val sourceTitle = film.originalDisplay?.title ?: film.title
        if (kind.isBlank() || unsafeParts.containsMatchIn(sourceTitle)) return null
        val (base, season) = titleParts(sourceTitle)
        val key = family(base)
        if (key.length < 2) return null
        val seasons = availableSeasons(film) + listOfNotNull(season)
        val hint = season ?: seasons.singleOrNull()
        return Candidate(ShowMember(film, hint, base), key, kind, seasons)
    }

    private fun yearsCompatible(members: List<ShowMember>): Boolean {
        val known = members.mapNotNull { it.film.year.toIntOrNull() }.filter { it in 1900..2100 }
        // Large gaps usually indicate a remake. Legitimate long-running shows with a
        // trusted provider ID bypass this conservative text-only check.
        return known.isEmpty() || known.max() - known.min() <= 8
    }

    private fun key(scope: String, mode: String, identity: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${scope.length}:$scope|$mode|$identity".toByteArray(Charsets.UTF_8))
        return "show:" + bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun makeGroup(scope: String, selected: List<Candidate>): ShowGroup {
        val members = selected.map { it.member }
        val officialTitles = members.filter { it.film.metadataSource == "TMDB" }
            .map { titleParts(it.film.title).first }.filter { it.isNotBlank() }
        val title = officialTitles.ifEmpty { members.map { it.baseTitle } }.distinct().sortedWith(
            compareByDescending<String> { it.any { ch -> ch in '\u4e00'..'\u9fff' } }
                .thenBy { it.length }.thenBy { it }
        ).first()
        val tmdbId = members.mapNotNull { it.film.tmdbTvId?.takeIf { id -> id > 0 } }.distinct().singleOrNull()
        val sharedFamily = selected.map { it.family to it.type }.distinct().singleOrNull()
        // When metadata enrichment later reveals the same TMDB ID, an already
        // visible strict-family card must not suddenly change LazyRow identity.
        val identity = if (sharedFamily != null) "title:${sharedFamily.second}:${sharedFamily.first}"
            else "tv:$tmdbId"
        return ShowGroup(key(scope, "series", identity),
            title, members.sortedWith(compareBy({ it.seasonHint ?: Int.MAX_VALUE }, { it.film.title }, { it.film.id })), tmdbId)
    }

    /** Same catalog only; no other TVBox site is scanned or inherited implicitly. */
    fun groups(films: List<Film>, scope: String): List<ShowGroup> {
        if (scope.isBlank()) return emptyList()
        val items = films.mapNotNull(::candidate)
        val used = mutableSetOf<String>()
        val groups = mutableListOf<ShowGroup>()
        // Authoritative provider identity can bridge aliases and different years.
        items.filter { it.member.film.tmdbTvId != null }.groupBy { it.member.film.tmdbTvId!! }
            .toSortedMap().forEach { (_, list) ->
                // A verified TV-series ID is strong enough to unify alternate
                // source entries for the SAME season (or even unnumbered ones).
                // Missing season numbers will not manufacture episode mappings.
                if (list.size >= 2) {
                    groups += makeGroup(scope, list)
                    used += list.map { it.member.film.id }
                }
            }
        // Fallback: EXACT normalized base title + same TV category + clear season
        // evidence. Different explicit TMDB IDs and distant years never text-merge.
        items.filter { it.member.film.id !in used }
            .groupBy { it.family to it.type }
            .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .forEach { (_, list) ->
                val ids = list.mapNotNull { it.member.film.tmdbTvId }.distinct()
                val seasonHints = list.flatMap { it.explicitSeasons }.distinct()
                val all = list.map { it.member }
                // Without an authoritative TV ID, a single numbered season
                // plus one unnumbered root title is NOT sufficient evidence.
                // Two distinct explicit seasons must be present in the catalog.
                if (list.size >= 2 && ids.size <= 1 && yearsCompatible(all) &&
                    seasonHints.size >= 2) {
                    groups += makeGroup(scope, list)
                }
            }
        return groups.sortedBy { it.key }
    }
}

/** The chosen stream always keeps the authoritative source Film object. */
internal data class GroupPlaybackRef(
    val film: Film,
    val source: PlaybackEpisode,
    val line: PlaybackLine,
    val season: Int,
    val number: Int
) {
    fun request(): PlaybackRequest {
        require(film.playbackEpisodes.any { it === source })
        require(source.lines.any { it.id == line.id && it.uri == line.uri })
        return PlaybackRequest(film.id, source.number, line.id)
    }
}

internal data class GroupEpisode(val season: Int, val number: Int, val sources: List<GroupPlaybackRef>) {
    fun preferred(previous: GroupPlaybackRef? = null): GroupPlaybackRef =
        sources.firstOrNull { previous != null && it.film.id == previous.film.id &&
            it.source.number == previous.source.number && it.line.id == previous.line.id }
            ?: sources.first()
}

internal data class GroupSeasonSection(val number: Int, val episodes: List<GroupEpisode>)
internal data class GroupSeasonLayout(val sections: List<GroupSeasonSection>) {
    fun episode(season: Int, number: Int): GroupEpisode? =
        sections.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == number }
}

internal object GroupSeasonIndex {
    fun from(group: ShowGroup): GroupSeasonLayout {
        val refs = mutableListOf<GroupPlaybackRef>()
        group.members.forEach { member ->
            val film = member.film
            film.playbackEpisodes.forEach { episode ->
                val pair = SeasonEpisodes.explicitEpisode(episode.title)
                episode.lines.forEach lineLoop@{ line ->
                    val lineSeason = SeasonEpisodes.explicitSeason(line.title)
                    if (pair != null && lineSeason != null && pair.first != lineSeason) return@lineLoop
                    if (member.seasonHint != null && pair != null && pair.first != member.seasonHint) return@lineLoop
                    if (member.seasonHint != null && lineSeason != null && lineSeason != member.seasonHint) return@lineLoop
                    val season = pair?.first ?: lineSeason ?: member.seasonHint ?: return@lineLoop
                    val number = pair?.second ?: SeasonEpisodes.explicitNumber(episode.title)
                        ?: episode.number.takeIf { member.seasonHint != null && it in 1..300 }
                        ?: return@lineLoop
                    refs += GroupPlaybackRef(film, episode, line, season, number)
                }
            }
        }
        val indexed = refs.groupBy { it.season to it.number }
        val entries = indexed.map { (coordinate, rows) ->
            GroupEpisode(coordinate.first, coordinate.second, rows.distinctBy {
                listOf(it.film.id, it.source.number.toString(), it.line.id, it.line.uri)
            })
        }
        val seasonNumbers = (group.members.mapNotNull { it.seasonHint } +
            entries.map { it.season }).distinct().sorted()
        return GroupSeasonLayout(seasonNumbers.map { season ->
            GroupSeasonSection(season, entries.filter { it.season == season }.sortedBy { it.number })
        })
    }

    fun latest(layout: GroupSeasonLayout, load: (GroupPlaybackRef) -> WatchPosition?): Pair<GroupPlaybackRef, WatchPosition>? =
        layout.sections.asSequence().flatMap { it.episodes.asSequence() }
            .flatMap { it.sources.asSequence().mapNotNull { ref -> load(ref)?.let { ref to it } } }
            .maxByOrNull { it.second.updatedAtMs }

    fun detailsNeeded(group: ShowGroup, maxMembers: Int = 6): List<Film> =
        group.members.map { it.film }.filter { it.playbackEpisodes.isEmpty() &&
            (it.cmsVodId != null || it.spiderVodId != null) }.take(maxMembers)
}
