package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M468PersonalQualityFallbackTest {
    private fun offer(id: String, source: String, quality: Int) =
        HerrinSourceMovie(source, HerrinSourceType.NAS,
            Film(id, "Interstellar", "", "电影", "2014", "", R.drawable.photofilm_orbit),
            "movie", resolutionClaim = quality)

    @Test fun highQualitySameHostFallsBackTo1080pWithoutCrossAccountCredentialLeak() {
        val items = listOf(
            offer("4k", "nas", 2160),
            offer("1080", "nas", 1080),
            offer("wrong-origin", "cloud", 720))
        val media = mapOf(
            "4k" to PlaybackMedia("https://nas.example.net/dav/Interstellar.4k.mkv",
                mapOf("Authorization" to "Basic same-nas")),
            "1080" to PlaybackMedia("https://nas.example.net/dav/Interstellar.1080.mkv",
                mapOf("Authorization" to "Basic same-nas")),
            "wrong-origin" to PlaybackMedia("https://cloud.example.org/Interstellar.720p.mkv",
                mapOf("Authorization" to "Basic other-account"))
        )
        val playlist = HerrinPersonalPlaylistBuilder.create(items) {
            PlaybackResult.Ready(media.getValue(it.film.id))
        }
        assertEquals(3, playlist.episodes.single().lines.size)
        val highest = playlist.mediaByLine.getValue("personal-1-0")
        assertEquals(listOf("https://nas.example.net/dav/Interstellar.1080.mkv"),
            highest.alternateUris)
        assertTrue(highest.safeNotFoundFallback)
        assertTrue(PlaybackFormatRetry.Session(highest.uri, highest.alternateUris, null,
            allowNotFoundFallback = highest.safeNotFoundFallback).next(2004, 404) != null)
        assertEquals("Basic same-nas", highest.headers["Authorization"])
        assertFalse(highest.alternateUris.any { it.contains("cloud.example.org") })
        val lower = playlist.mediaByLine.getValue("personal-1-1")
        assertTrue(lower.alternateUris.contains("https://nas.example.net/dav/Interstellar.4k.mkv"))
    }

    @Test fun sameHostDifferentAuthNeverBecomesAutomaticAlternate() {
        val a = offer("a", "nas", 2160)
        val b = offer("b", "nas", 1080)
        val playlist = HerrinPersonalPlaylistBuilder.create(listOf(a, b)) {
            PlaybackResult.Ready(PlaybackMedia("https://nas.example.net/dav/" + it.film.id + ".mp4",
                headers = mapOf("Authorization" to it.film.id)))
        }
        assertTrue(playlist.mediaByLine.values.all { it.alternateUris.isEmpty() })
        assertTrue(playlist.mediaByLine.values.none { it.safeNotFoundFallback })
    }
}
