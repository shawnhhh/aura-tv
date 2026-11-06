package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HerrinPlaybackClosureTest {
    private val film = Film("provider-id", "Original", "", "剧集", "", "", 0,
        spiderVodId = "vod-original", playbackEpisodes = listOf(
            PlaybackEpisode(7, "S2E10", listOf(PlaybackLine("bad", "4K", "opaque-bad"),
                PlaybackLine("hd", "1080p", "opaque-hd"), PlaybackLine("sd", "720p", "opaque-sd"))),
            PlaybackEpisode(8, "S2E11", listOf(PlaybackLine("other-episode", "4K", "opaque-11")))))
    private val failure = FongMiPlaybackRecovery.Failure(FongMiPlaybackRecovery.Kind.PARSE)
    private val key = HerrinAutomaticPlayback.Key("approved-root", "tmdb-tv-42", HerrinPlaybackCoordinate(2, 10))

    @Test fun tmdbDisplayIdentityNeverReplacesTheSignedProviderFilm() {
        val owner = SpiderFilmAuthority("site", "runtime", "approved")
        val raw = owner.sign(film)
        val display = raw.copy(id = "tmdb-tv-42", title = "TMDB display", spiderOrigin = null)
        val routes = HerrinTmdbPlaybackRouteStore()
        routes.spider(display.id, "site", raw)
        assertFalse(owner.accepts(display))
        assertSame(raw, routes.sourceFilm(display.id))
        assertTrue(owner.accepts(routes.sourceFilm(display.id)!!))
        assertNull(routes.usable(display.id, "different-site"))
        routes.clear()
        assertNull(routes.sourceFilm(display.id))
    }

    @Test fun fallbackUsesSameEpisodeAndVerifiedQualityOrderWithoutRepeatingCallbacks() {
        val session = HerrinAutomaticPlayback()
        assertEquals(HerrinAutomaticPlayback.Next.Line("hd"),
            session.next(key, "source", film, 7, "bad", true, failure))
        assertNull(session.next(key, "source", film, 7, "bad", true, failure))
        assertEquals(HerrinAutomaticPlayback.Next.Line("sd"),
            session.next(key, "source", film, 7, "hd", true, failure))
        assertEquals(HerrinAutomaticPlayback.Next.Source,
            session.next(key, "source", film, 7, "sd", true, failure))
        session.clear()
        assertEquals(HerrinAutomaticPlayback.Next.Line("hd"),
            session.next(key, "source", film, 7, "bad", true, failure))
    }

    @Test fun authorizationDenialsNeverActivateAnotherLineOrProvider() {
        val session = HerrinAutomaticPlayback()
        for (reason in listOf("解析接口 HTTP 403", "未授权的 Spider", "安全校验 MD5 不一致")) {
            assertNull(session.next(key, "source", film, 7, "bad", true,
                HerrinAutomaticPlayback.resolutionFailure(reason)))
        }
        assertNull(session.next(key, "source", film, 7, "bad", true,
            FongMiPlaybackRecovery.Failure(FongMiPlaybackRecovery.Kind.NETWORK_POLICY)))
    }

    @Test fun providerSearchIsFiniteAndScopedToOneLogicalEpisode() {
        val session = HerrinAutomaticPlayback()
        val single = film.copy(playbackEpisodes = listOf(film.playbackEpisodes.first().copy(
            lines = listOf(film.playbackEpisodes.first().lines.first()))))
        assertEquals(HerrinAutomaticPlayback.Next.Source, session.next(key, "a", single, 7, "bad", true, failure))
        assertEquals(HerrinAutomaticPlayback.Next.Source, session.next(key, "b", single, 7, "bad", true, failure))
        assertNull(session.next(key, "c", single, 7, "bad", true, failure))
        assertEquals(HerrinAutomaticPlayback.Next.Source, session.next(
            key.copy(coordinate = HerrinPlaybackCoordinate(2, 11)), "a", single, 7, "bad", true, failure))
    }

    @Test fun sourceSelectorMapsLogicalEpisodeWithoutJumpingSeasonOrFirstEntry() = runBlocking {
        val choices = mutableListOf<Pair<Int, String>>()
        val selected = HerrinPlayableSourceSelector.find(film,
            coordinate = HerrinPlaybackCoordinate(2, 10),
            resolve = { episode, line ->
                choices += episode to line
                if (line == "bad") PlaybackResult.Unavailable("parse failed")
                else PlaybackResult.Ready(PlaybackMedia("https://example.invalid/$line.mp4"))
            }, verifyMedia = { true })
        assertEquals(7 to "hd", selected?.let { it.episode to it.line })
        assertEquals(listOf(7 to "bad", 7 to "hd"), choices)
        assertNull(HerrinPlayableSourceSelector.find(film, coordinate = HerrinPlaybackCoordinate(1, 10),
            resolve = { _, _ -> error("Wrong season must never resolve") }, verifyMedia = { true }))
    }
    @Test fun unlabelledSeasonOrListOrdinalCannotSelectAnUnrelatedEpisodeOnAnotherProvider() {
        val unlabelled = film.copy(playbackEpisodes = listOf(film.playbackEpisodes.first().copy(title = "extra")))
        val coordinate = HerrinPlaybackCoordinate.from(unlabelled, 7, "bad")!!
        assertFalse(coordinate.canMapAcrossSources)
        val session = HerrinAutomaticPlayback()
        val context = key.copy(coordinate = coordinate)
        assertEquals(HerrinAutomaticPlayback.Next.Line("hd"),
            session.next(context, "source", unlabelled, 7, "bad", true, failure))
        session.next(context, "source", unlabelled, 7, "hd", true, failure)
        assertNull(session.next(context, "source", unlabelled, 7, "sd", true, failure))
    }
}
