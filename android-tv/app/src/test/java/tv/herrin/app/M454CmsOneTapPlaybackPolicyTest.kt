package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M454CmsOneTapPlaybackPolicyTest {
    @Test fun aSelectedCmsListingWithoutEpisodesOpensPlayerInsteadOfTechnicalDetail() {
        assertTrue(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = false, spiderActive = false,
            cmsVodId = "vod-902", hasPlayableEpisode = false))
    }

    @Test fun sourceMustBeExplicitlySelectedAndHaveOriginalVodId() {
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = false, groupedSeason = false, spiderActive = false,
            cmsVodId = "vod-902", hasPlayableEpisode = false))
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = false, spiderActive = false,
            cmsVodId = null, hasPlayableEpisode = false))
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = false, spiderActive = false,
            cmsVodId = "", hasPlayableEpisode = false))
    }

    @Test fun groupedSeasonsAndSpiderKeepTheirSeparateExistingPaths() {
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = true, spiderActive = false,
            cmsVodId = "vod-902", hasPlayableEpisode = false))
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = false, spiderActive = true,
            cmsVodId = "vod-902", hasPlayableEpisode = false))
    }

    @Test fun aMediaAlreadyContainingPlayableEpisodesNeedsNoExtraHttpLookup() {
        assertFalse(CmsOneTapPlaybackPolicy.resolveSelectedFilmInPlayer(
            siteSelected = true, groupedSeason = false, spiderActive = false,
            cmsVodId = "vod-902", hasPlayableEpisode = true))
    }

    @Test fun firstPlayableSkipsNonHttpsLinkAndSelectsValidBackup() {
        val film = DemoCatalog().titles().first().copy(
            playbackEpisodes = listOf(
                PlaybackEpisode(1, "第一集", listOf(
                    PlaybackLine("bad-http", "仅 HTTP", "http://example.test/movie.m3u8"),
                    PlaybackLine("bad-opaque", "脚本", "javascript:alert(1)"))),
                PlaybackEpisode(2, "第二集", listOf(
                    PlaybackLine("valid", "授权 HTTPS", "https://licensed.example.test/movie.m3u8")))
            ))
        assertEquals(2 to "valid", CmsOneTapPlaybackPolicy.firstPlayable(film))
        assertTrue(LicensedPlaybackResolver.resolve(film, 2, "valid") is PlaybackResult.Ready)
    }

    @Test fun firstPlayableSkipsInvalidFirstLineInTheSameEpisode() {
        val film = DemoCatalog().titles().first().copy(
            playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(
                PlaybackLine("invalid", "解析占位", "plugin://untrusted"),
                PlaybackLine("official", "授权源", "https://licensed.example.test/film.mp4")))))
        assertEquals(1 to "official", CmsOneTapPlaybackPolicy.firstPlayable(film))
    }

    @Test fun noSupportedHttpsLineNeverBecomesAPlayableFilm() {
        val film = DemoCatalog().titles().first().copy(
            playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(
                PlaybackLine("http", "不支持的非加密链接", "http://licensed.example.test/film.mp4"),
                PlaybackLine("opaque", "插件标识", "csp_Invalid")))))
        assertNull(CmsOneTapPlaybackPolicy.firstPlayable(film))
    }

    @Test fun demoWithoutMediaDoesNotInventPlayUrls() {
        val demo = DemoCatalog().titles().first()
        assertNull(CmsOneTapPlaybackPolicy.firstPlayable(demo))
    }
}
