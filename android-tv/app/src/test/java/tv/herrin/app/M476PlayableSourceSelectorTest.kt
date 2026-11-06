package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M476PlayableSourceSelectorTest {
    private fun film() = Film("tmdb-movie-1", "Test Movie", "", "电影",
        "2026", "", R.drawable.photofilm_orbit,
        playbackEpisodes = listOf(
            PlaybackEpisode(1, "正片", listOf(
                PlaybackLine("ultra-broken", "4K Dolby Vision",
                    "https://example.test/4k.m3u8"),
                PlaybackLine("hd-ready", "1080p HLS",
                    "https://example.test/1080.m3u8"),
                PlaybackLine("low-ready", "720p MP4",
                    "https://example.test/720.mp4")
            ))))

    @Test fun broken4kFallsThroughToVerified1080AndDoesNotChoose720() = runBlocking {
        val visited = mutableListOf<String>()
        val verified = mutableListOf<String>()
        val selected = HerrinPlayableSourceSelector.find(film(), { episode, line ->
            assertEquals(1, episode)
            visited += line
            PlaybackResult.Ready(PlaybackMedia("https://example.test/$line"))
        }, { media ->
            verified += media.uri.substringAfterLast('/')
            !media.uri.endsWith("/ultra-broken")
        })
        assertEquals("hd-ready", selected?.line)
        assertEquals(1080, selected?.claimedResolution)
        assertEquals(listOf("ultra-broken", "hd-ready"), visited)
        assertEquals(listOf("ultra-broken", "hd-ready"), verified)
    }

    @Test fun playerContentFailureAndHttp403DoNotAdvertiseAWorkingMovie() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = HerrinPlayableSourceSelector.find(film(), { _, line ->
            calls += line
            if (line == "ultra-broken") PlaybackResult.Unavailable("PROXY_INVALID_ROUTE")
            else PlaybackResult.Ready(PlaybackMedia("https://example.test/$line"))
        }, { false })
        assertNull(selected)
        assertEquals(listOf("ultra-broken", "hd-ready", "low-ready"), calls)
    }

    @Test fun deadPrimaryMustNotHideVerifiedProviderBackupBeforePlayerStarts() = runBlocking {
        val tested = mutableListOf<String>()
        val primary = "https://stream.example.test/dead.mp4"
        val backup = "https://stream.example.test/works.mp4"
        val response = PlaybackMedia(primary, alternateUris = listOf(backup, primary))
        val selected = HerrinPlayableSourceSelector.find(film(), { _, line ->
            if (line == "ultra-broken") PlaybackResult.Ready(response)
            else PlaybackResult.Unavailable("no need")
        }, { media ->
            tested += media.uri
            media.uri == backup
        })
        assertEquals("ultra-broken", selected?.line)
        assertEquals(backup, selected?.media?.uri)
        assertTrue("Failed primary must not be returned as a player fallback",
            selected?.media?.alternateUris.orEmpty().isEmpty())
        assertEquals(listOf(primary, backup), tested)
    }

    @Test fun sensitiveHeadersNeverProbeUnrelatedAlternateOrigin() = runBlocking {
        val checked = mutableListOf<String>()
        val origin = "https://private.example.test/expired.m3u8"
        val other = "https://unrelated.example.test/backup.m3u8"
        val chosen = HerrinPlayableSourceSelector.find(film(), { _, line ->
            if (line == "ultra-broken") PlaybackResult.Ready(PlaybackMedia(origin,
                headers = mapOf("Authorization" to "Bearer PRIVATE"),
                alternateUris = listOf(other)))
            else PlaybackResult.Unavailable("missing")
        }, { media ->
            checked += media.uri
            media.uri == other
        })
        assertNull(chosen)
        assertEquals(listOf(origin), checked)
    }

    @Test fun neverChecksMoreThanSixAuthorizedLines() = runBlocking {
        val ten = film().copy(playbackEpisodes = listOf(PlaybackEpisode(1, "正片",
            (1..10).map { PlaybackLine("line-$it", "1080p", "https://source.test/$it") })))
        var calls = 0
        assertNull(HerrinPlayableSourceSelector.find(ten, { _, _ ->
            calls++
            PlaybackResult.Unavailable("no media")
        }, { true }))
        assertEquals(6, calls)
    }

    @Test fun firstEpisodeOnlyIfAnotherEpisodeHasNoValidLine() = runBlocking {
        val second = film().copy(playbackEpisodes = film().playbackEpisodes +
            PlaybackEpisode(2, "下集", listOf(
                PlaybackLine("s1e2", "1080p", "https://example.test/ep2.mp4"))))
        val selected = HerrinPlayableSourceSelector.find(second, { episode, id ->
            PlaybackResult.Ready(PlaybackMedia("https://example.test/$id"))
        }, { it.uri.endsWith("/s1e2") })
        assertEquals(2, selected?.episode)
        assertEquals("s1e2", selected?.line)
    }
}
