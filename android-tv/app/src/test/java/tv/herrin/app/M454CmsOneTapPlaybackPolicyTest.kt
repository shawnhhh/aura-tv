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

    @Test fun demoWithoutMediaDoesNotInventPlayUrls() {
        val demo = DemoCatalog().titles().first()
        assertNull(CmsOneTapPlaybackPolicy.firstPlayable(demo))
    }
}
