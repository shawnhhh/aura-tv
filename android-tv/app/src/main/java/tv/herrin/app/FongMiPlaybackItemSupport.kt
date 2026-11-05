/*
 * FongMi/TV player/media/MediaItemFactory.java and player/exo/ExoUtil.java
 * upstream fongmi@305dff233cde512e2c83f71529b7c3ecfd61fefc. Original author FongMi.
 * GPL-3.0-only; see android-tv/LICENSE-GPL-3.0.md.
 * Host adaptation: explicit Sub.rawFlag travels through the approved Spider IPC;
 * language defaults apply only when the source did not specify flags.
 */
package tv.herrin.app

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import java.util.Locale

/**
 * Real, compatible playback operations that do not require the unreleased
 * custom Media3 fork or any FFmpeg/mpv native binary.
 */
internal object FongMiPlaybackItemSupport {
    private val preferredText = listOf("zh", "zho", "chi")

    /**
     * FongMi MediaItemFactory.SubtitleFlags.create/get:
     * - a single subtitle is default
     * - multiple tracks prefer the user's preferred language
     * - otherwise the first is default
     *
     * An explicit flag wins; other tracks then use AUTOSELECT, as upstream.
     */
    fun subtitleSelectionFlags(
        languages: List<String?>,
        preferredLanguages: List<String> = preferredText,
        rawFlags: List<Int> = List(languages.size) { 0 }
    ): List<Int> {
        require(rawFlags.size == languages.size)
        if (languages.isEmpty()) return emptyList()
        val flags = rawFlags.map(FongMiSubtitleFormats::selectionFlag)
        if (flags.any { it != 0 }) return flags.map { if (it == 0) C.SELECTION_FLAG_AUTOSELECT else it }
        val preferred = preferredLanguages.map { normalizeLanguage(it) }
            .filter { it.isNotBlank() }.distinct()
        var bestIndex = 0
        var bestRank = Int.MAX_VALUE
        languages.forEachIndexed { index, language ->
            val actual = normalizeLanguage(language)
            if (actual.isBlank()) return@forEachIndexed
            val rank = preferred.indexOfFirst { target ->
                actual == target || actual.startsWith("$target-")
            }
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank
                bestIndex = index
            }
        }
        return languages.indices.map {
            if (it == bestIndex) C.SELECTION_FLAG_DEFAULT else C.SELECTION_FLAG_AUTOSELECT
        }
    }

    private fun normalizeLanguage(value: String?): String =
        value.orEmpty().trim().replace('_', '-').lowercase(Locale.ROOT)

    /**
     * FongMi ExoUtil.extractHeaders: reverse a MediaItem's per-session
     * RequestMetadata extras. Never share or log credential-bearing headers.
     */
    fun extractHeaders(mediaItem: MediaItem): Map<String, String> {
        val extras = mediaItem.requestMetadata.extras ?: return emptyMap()
        return extras.keySet().sorted().mapNotNull { name ->
            extras.getString(name)?.let { name to it }
        }.toMap(LinkedHashMap())
    }
}
