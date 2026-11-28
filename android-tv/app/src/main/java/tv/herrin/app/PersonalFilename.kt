package tv.herrin.app

import java.text.Normalizer

/** Parse lookup evidence without changing the stored remote name or path. */
internal object PersonalFilename {
    private const val HAN_DIGITS = "零〇一二两兩三四五六七八九十百"
    private val pair = Regex("""(?i)(?<![a-z0-9])(?:s|season[ ._-]*)(\d{1,2})[ ._-]*(?:e|episode[ ._-]*)(\d{1,3})(?!\d)""")
    private val ep = Regex("""(?i)(?<![a-z0-9])(?:ep(?:isode)?|e)[ ._-]*(\d{1,3})(?![a-z0-9])""")
    private val cnEpisode = Regex("""第\s*([0-9${HAN_DIGITS}]{1,6})\s*[集话話]""")
    private val cnSeason = Regex("""第\s*([0-9${HAN_DIGITS}]{1,4})\s*季""")
    private val seasonFolder = Regex("""(?i)(?:season[ ._-]*|s)(\d{1,2})""")
    private val bracketEpisode = Regex("""(?i)(?:\[| - )(\d{2,3})(?:v\d)?(?=]|[ ._-]|$)""")
    private val special = Regex("""(?i)(?<![a-z0-9])(?:OVA|OAD|SP|Special)[ ._-]*(\d{1,3})(?!\d)""")
    private val year = Regex("""(?<![\p{L}\p{N}])((?:19|20)\d{2})(?![\p{L}\p{N}])""")
    private val disc = Regex("""(?i)(?:^|[ ._-])(?:CD|Disc|Disk)[ ._-]*(\d{1,2})$""")
    private val part = Regex("""(?i)(?:^|[ ._-])Part[ ._-]*(\d{1,2})$""")
    private val containers = Regex("""(?i)BDMV|STREAM|CERTIFICATE|VIDEO_TS|BD[ ._-]*\d+|Extras?|花絮|特典|SPs?|Specials?|OVA|OAD""")
    private val generic = Regex("""(?i)Movies?|TV(?:[ ._-]*Shows?)?|Series|Anime|Videos?|Media|4K|UHD|1080p|电影|影片|电视剧|剧集|美剧|英剧|日剧|韩剧|港剧|國劇|国产剧|動漫|动漫|动画|動畫""")
    private val releaseNoise = Regex("""(?i)(?<![a-z0-9])(?:blu[ ._-]?ray|remux|uhd|proper|repack|web[ ._-]?rip|dvd[ ._-]?rip|av1|10bit|8bit|ddp?[ .]?\d(?:\.\d)?|flac|truehd|multi)(?![a-z0-9])""")
    private val bracketNoise = Regex("""(?i)\[[^]]*(?:字幕|sub|rip|web|x26|hevc|4k|1080|2160|[a-f0-9]{8})[^]]*]""")
    private val numeric = Regex("""(?i)(\d{1,3})(?:v\d)?""")
    private val idTag = Regex("""(?i)[\[{](tmdb|imdb)(?:id)?[=:-]((?:tt)?\d{1,10})[\]}]""")
    private val releaseOnly = Regex("""(?i)(?:[ ._\[\]-]*(?:\d{3,4}p|4k|uhd|hdr10\+?|hdr|dv|remux|blu[ ._-]?ray|web[ ._-]?(?:dl|rip)|[hx][ ._-]?26[45]|hevc|av1|10bit|aac|truehd|flac))+[ ._\[\]-]*""")

    fun parse(name: String, parentPath: String): PersonalStoragePolicy.Name {
        val raw = normalized(name.substringBeforeLast('.'))
        val parents = parentPath.split('/').map(::normalized).filter(String::isNotBlank)
        val contextSeason = parents.asReversed().firstNotNullOfOrNull(::folderSeason)
        val parentIndex = parents.indexOfLast {
            folderSeason(it) == null && !containers.matches(it) && !generic.matches(it) && !releaseOnly.matches(it)
        }
        val parent = parents.getOrNull(parentIndex).orEmpty()
        val paired = pair.find(raw)
        val chinese = cnEpisode.find(raw)
        val english = ep.find(raw)
        val extra = special.find(raw)
        val bracket = bracketEpisode.find(raw)?.takeIf {
            parent.isNotBlank() && it.groupValues[1].toInt() !in setOf(480, 576, 720)
        }
        val marker = paired ?: chinese ?: english ?: extra ?: bracket
        var stripped = marker?.let { raw.replaceRange(it.range, " ") } ?: raw
        val explicitSeason = cnSeason.find(stripped)
        if (explicitSeason != null) stripped = stripped.replaceRange(explicitSeason.range, " ")
        if (marker != null) stripped = stripped.replace(Regex("""^\[[^]]{1,80}]\s*"""), "")
        val cleaned = clean(stripped)
        val numberedFile = numeric.matchEntire(cleaned)?.groupValues?.get(1)?.toIntOrNull()
        val episode = when (marker) {
            paired -> paired?.groupValues?.get(2)?.toIntOrNull()
            chinese -> chinese?.groupValues?.get(1)?.let(::number)
            else -> marker?.groupValues?.get(1)?.toIntOrNull()
        } ?: numberedFile?.takeIf { contextSeason != null && parent.isNotBlank() }
        val season = paired?.groupValues?.get(1)?.toIntOrNull()
            ?: explicitSeason?.groupValues?.get(1)?.let(::number)
            ?: 0.takeIf { extra != null || parents.any { it.matches(Regex("(?i)SPs?|Specials?|OVA|OAD|特典")) } }
            ?: contextSeason ?: if (episode != null) 1 else null
        val parentTitle = clean(parent)
        val episodeOnly = marker?.let { raw.take(it.range.first).trim(' ', '.', '_', '-').isEmpty() } == true
        val directoryEvidence = parentTitle.isNotBlank() && (episodeOnly ||
            episode != null && (contextSeason != null && numeric.matches(cleaned) || cleaned.isBlank()) ||
            releaseOnly.matches(stripped) ||
            parents.any { it.equals("BDMV", true) } && raw.matches(Regex("""\d{5}""")))
        val fileTitle = if (directoryEvidence) parentTitle else cleaned
        val segment = disc.find(fileTitle)
        // “Part 2” can be a distinct movie. Only reinterpret it as a file
        // segment if the nearby work directory independently names the base.
        val confirmedPart = part.find(fileTitle)?.takeIf {
            parentTitle.isNotBlank() && ArtworkTitle.normalize(fileTitle.take(it.range.first)) == ArtworkTitle.normalize(parentTitle)
        }
        val suffix = segment ?: confirmedPart
        val title = (suffix?.let { fileTitle.take(it.range.first).trim() } ?: fileTitle).ifBlank { raw }
        val variants = title.split(Regex("""\s*[|／/]\s*|\s+[-–—]\s+"""))
            .filter { it.length >= 2 && it.any(Char::isLetter) }
            .take(3)
        val releaseYear = findYear(if (directoryEvidence) parent else stripped) ?: findYear(parent)
        val sameWorkDirectory = directoryEvidence || parentTitle.isNotBlank() &&
            ArtworkTitle.normalize(title) == ArtworkTitle.normalize(parentTitle) &&
            (episode != null || releaseYear != null)
        val tags = listOf(raw, parent).flatMap { idTag.findAll(it).toList() }
        val tmdb = tags.filter { it.groupValues[1].equals("tmdb", true) }
            .mapNotNull { it.groupValues[2].toIntOrNull()?.takeIf { id -> id > 0 } }.distinct().singleOrNull()
        val imdb = tags.filter { it.groupValues[1].equals("imdb", true) }
            .map { it.groupValues[2].lowercase() }.filter { it.matches(Regex("""tt\d{7,10}""")) }
            .distinct().singleOrNull()
        return PersonalStoragePolicy.Name(title, releaseYear, season, episode?.takeIf { it in 1..999 },
            variants.filter { it != title }, suffix?.groupValues?.get(1)?.toIntOrNull(),
            parents.take(parentIndex + 1).joinToString("/").takeIf { sameWorkDirectory },
            tmdb, imdb)
    }

    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("""\p{Cf}"""), "").trim()

    private fun folderSeason(value: String): Int? =
        seasonFolder.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: cnSeason.matchEntire(value)?.groupValues?.get(1)?.let(::number)

    private fun clean(value: String): String {
        val plain = value.replace(idTag, " ").replace(bracketNoise, " ").replace(releaseNoise, " ")
        val release = year.findAll(plain).lastOrNull { it.range.first > 0 && plain.take(it.range.first).any(Char::isLetter) }
        // ArtworkTitle also handles isolated resolution/codec/language markers.
        return ArtworkTitle.searchQuery(release?.let { plain.replaceRange(it.range, " ") } ?: plain)
            .trim(' ', '-', '[', ']', '(', ')')
    }

    private fun findYear(value: String): Int? = year.findAll(value).lastOrNull {
        it.range.first > 0 && value.take(it.range.first).any(Char::isLetter)
    }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= java.time.Year.now().value + 1 }

    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var digit = 0
        for (c in raw) when (c) {
            '十', '百' -> {
                total += (if (digit == 0) 1 else digit) * if (c == '十') 10 else 100
                digit = 0
            }
            else -> digit = digits[c] ?: return null
        }
        return total + digit
    }
}
