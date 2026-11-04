package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Catches unauthorized worker dispatch, wrong selections, and lost source media metadata. */
class HerrinPlaybackAdapterTest {
    private val film = Film("film", "Film", "", "电影", "2026", "", 0,
        playbackEpisodes = listOf(PlaybackEpisode(3, "Third", listOf(
            PlaybackLine("line-b", "VIP", "https://media.example/third.mp4",
                listOf(AuthorizedSubtitle("en", "English", "https://media.example/en.vtt")))))))

    @Test fun rejectsDifferentApprovedSpiderBeforeExecutingSource() = runBlocking {
        val adapter = HerrinPlaybackAdapter(spider = { error("unauthorized execution") },
            cms = { error("wrong source") })
        val result = adapter.resolve(HerrinPlaybackRequest(film, 3, "line-b",
            HerrinPlaybackSource.Spider("selected-site"), HerrinPlaybackAuthorization("other-site")))
        assertTrue(result is PlaybackResult.Unavailable)
    }

    @Test fun rejectsMissingApprovalBeforeExecutingSource() = runBlocking {
        val adapter = HerrinPlaybackAdapter(spider = { error("unauthorized execution") },
            cms = { error("wrong source") })
        assertTrue(adapter.resolve(HerrinPlaybackRequest(film, 3, "line-b",
            HerrinPlaybackSource.Spider("selected-site"))) is PlaybackResult.Unavailable)
    }

    @Test fun missingSelectionNeverFallsThroughToAnotherEpisode() = runBlocking {
        val adapter = HerrinPlaybackAdapter(spider = { error("missing selection executed") },
            cms = { error("missing selection executed") })
        assertTrue(adapter.resolve(HerrinPlaybackRequest(film, 1, "line-b",
            HerrinPlaybackSource.Direct)) is PlaybackResult.Unavailable)
        assertTrue(adapter.resolve(HerrinPlaybackRequest(film, 3, "missing",
            HerrinPlaybackSource.Direct)) is PlaybackResult.Unavailable)
    }

    @Test fun blankSelectedUriNeverInvokesTheSource() = runBlocking {
        val blank = film.copy(playbackEpisodes = listOf(PlaybackEpisode(3, "Third",
            listOf(PlaybackLine("line-b", "VIP", "")))))
        val adapter = HerrinPlaybackAdapter(spider = { error("blank source executed") },
            cms = { error("blank source executed") })
        assertTrue(adapter.resolve(HerrinPlaybackRequest(blank, 3, "line-b",
            HerrinPlaybackSource.Spider("site"), HerrinPlaybackAuthorization("site")))
            is PlaybackResult.Unavailable)
    }

    @Test fun exactDirectSelectionRetainsSubtitlesWithoutExecutingPluginOrParser() = runBlocking {
        val adapter = HerrinPlaybackAdapter(spider = { error("direct plugin execution") },
            cms = { error("direct parser execution") })
        val result = adapter.resolve(HerrinPlaybackRequest(film, 3, "line-b",
            HerrinPlaybackSource.Direct)) as PlaybackResult.Ready
        assertEquals("https://media.example/third.mp4", result.media.uri)
        assertEquals(listOf(AuthorizedSubtitle("en", "English", "https://media.example/en.vtt")),
            result.media.subtitles)
    }

    @Test fun approvedSpiderDispatchesExactSelectionOnceAndPreservesNativeMedia() = runBlocking {
        var calls = 0
        val adapter = HerrinPlaybackAdapter(spider = { request ->
            calls++
            assertEquals(3, request.episode)
            assertEquals("line-b", request.line)
            PlaybackResult.Ready(PlaybackMedia("http://127.0.0.1:1234/proxy/session",
                headers = mapOf("Range" to "bytes=0-")))
        }, cms = { error("second parsing") })
        val result = adapter.resolve(HerrinPlaybackRequest(film, 3, "line-b",
            HerrinPlaybackSource.Spider("selected-site"), HerrinPlaybackAuthorization("selected-site")))
            as PlaybackResult.Ready
        assertEquals(1, calls)
        assertEquals("http://127.0.0.1:1234/proxy/session", result.media.uri)
        assertEquals(mapOf("Range" to "bytes=0-"), result.media.headers)
    }

    @Test fun cmsUsesOnlyItsNativeResolverAndPropagatesFailure() = runBlocking {
        var calls = 0
        val adapter = HerrinPlaybackAdapter(spider = { error("wrong plugin execution") },
            cms = { request ->
                calls++
                assertEquals("cms-original-id", request.film.cmsVodId)
                assertEquals(3, request.episode)
                assertEquals("line-b", request.line)
                PlaybackResult.Unavailable("PARSE_HTTP:403")
            })
        val result = adapter.resolve(HerrinPlaybackRequest(film.copy(cmsVodId = "cms-original-id"),
            3, "line-b", HerrinPlaybackSource.Cms(TvBoxSite("CMS", false, key = "cms"))))
        assertEquals(PlaybackResult.Unavailable("PARSE_HTTP:403"), result)
        assertEquals(1, calls)
    }

    @Test fun personalMediaRetainsCredentialsAndAlternatesWithoutParser() = runBlocking {
        val media = PlaybackMedia("https://nas.example/third.mp4", mapOf("Authorization" to "fixture"),
            alternateUris = listOf("https://nas.example/backup.mp4"))
        val adapter = HerrinPlaybackAdapter(spider = { error("plugin execution") },
            cms = { error("parser execution") })
        val result = adapter.resolve(HerrinPlaybackRequest(film, 3, "line-b",
            HerrinPlaybackSource.Direct, personalMedia = media)) as PlaybackResult.Ready
        assertSame(media, result.media)
    }
}
