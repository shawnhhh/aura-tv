package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SpiderFilmAuthorityTest {
    private val film = Film("source-film", "Original", "", "电影", "", "", 0,
        spiderVodId = "original-vod", playbackEpisodes = listOf(
            PlaybackEpisode(1, "One", listOf(PlaybackLine("main", "flag", "opaque-1"))),
            PlaybackEpisode(2, "Two", listOf(PlaybackLine("main", "flag", "opaque-2")))))
    private val owner = SpiderFilmAuthority("Aa", "full-runtime-a", "approved-session")

    @Test fun displayEnrichmentDoesNotChangePlaybackAuthority() {
        val signed = owner.sign(film)
        assertTrue(owner.accepts(signed.copy(title = "TMDB title", synopsis = "metadata")))
        assertFalse(owner.accepts(signed.copy(id = "other-film")))
        assertFalse(owner.accepts(signed.copy(spiderVodId = "other-vod")))
    }
    @Test fun fullSiteAndGrantAreRequiredEvenForCollidingLegacyHashCodes() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val signed = owner.sign(film)
        assertFalse(SpiderFilmAuthority("BB", "full-runtime-b", "approved-session").accepts(signed))
        assertFalse(SpiderFilmAuthority("Aa", "full-runtime-a", "revoked-session").accepts(signed))
        assertFalse(owner.accepts(film))
    }
    @Test fun foreignFilmLineAndChangedEpisodeOrUriAreRejected() {
        val signed = owner.sign(film)
        val episode = signed.playbackEpisodes.first()
        fun changed(line: PlaybackLine, number: Int = 1) = signed.copy(
            playbackEpisodes = listOf(episode.copy(number = number, lines = listOf(line))))
        assertFalse(owner.accepts(changed(episode.lines.single().copy(uri = "injected"))))
        assertFalse(owner.accepts(changed(episode.lines.single(), 2)))
        val foreign = owner.sign(film.copy(id = "other", spiderVodId = "other-vod"))
        assertFalse(owner.accepts(changed(foreign.playbackEpisodes.first().lines.single())))
    }
    @Test fun boundedIpcMayOmitEpisodesWithoutAuthorizingDifferentLines() {
        val signed = owner.sign(film)
        assertTrue(owner.accepts(signed.copy(playbackEpisodes = signed.playbackEpisodes.take(1))))
        val session = SpiderSourceSession()
        val ticket = session.snapshot()
        val secret = session.filmSecret(ticket)!!
        val browse = session.beginBrowse(ticket)!!
        assertEquals(secret, session.filmSecret(browse))
        session.revoke()
        assertNotEquals(secret, session.filmSecret(session.snapshot()))
        assertNull(session.filmSecret(browse))
    }
    @Test fun playerIdentityUsesFullVodIdEvenWhenOldFilmHashesCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val a = owner.sign(film.copy(id = "same-legacy-hash", spiderVodId = "Aa"))
        val b = owner.sign(film.copy(id = "same-legacy-hash", spiderVodId = "BB"))
        assertNotEquals(owner.mediaId(a, 1, "main"), owner.mediaId(b, 1, "main"))
        assertNotEquals(owner.mediaId(a, 1, "main"), owner.mediaId(a, 2, "main"))
    }
}
