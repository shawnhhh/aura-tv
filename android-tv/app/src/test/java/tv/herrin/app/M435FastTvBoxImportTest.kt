package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/** Importing a TVBox warehouse must never probe unselected Spider plugins. */
class M435FastTvBoxImportTest {
    private val first = TvBoxSite(name = "已选站点", needsAdapter = true,
        key = "chosen", spiderApi = "csp_Chosen")
    private val unrelated = TvBoxSite(name = "需扫码网盘", needsAdapter = true,
        key = "cloud", spiderApi = "csp_Cloud")
    private val direct = TvBoxSite(name = "CMS", needsAdapter = false,
        key = "cms", apiUrl = "https://licensed.example/api.php")
    private val config = TvBoxConfig.Single(listOf(first, unrelated, direct),
        lives = emptyList(), hasExternalEngine = true, spiderJar = "https://licensed.example/spider.jar;md5;abcdef")

    @Test fun freshImportOnlyListsAllSitesWithoutRunningAnyPlugin() {
        assertEquals(3, config.sites.size)
        assertTrue(TvBoxAutoSites.resumeOnly(config, "").isEmpty())
        assertEquals(3, TvBoxAutoSites.candidates(config, "").size)
    }

    @Test fun exactPreviouslySelectedSiteRestoresOnceWithoutProbingOthers() {
        val pin = TvBoxAutoSites.identity(unrelated, config.spiderJar)
        assertEquals(listOf(unrelated), TvBoxAutoSites.resumeOnly(config, pin))
        val cmsPin = TvBoxAutoSites.identity(direct, config.spiderJar)
        assertEquals(listOf(direct), TvBoxAutoSites.resumeOnly(config, cmsPin))
    }

    @Test fun changingPluginOrSiteInvalidatesOldSelection() {
        val pin = TvBoxAutoSites.identity(first, config.spiderJar)
        assertEquals(listOf(first), TvBoxAutoSites.resumeOnly(config, pin))
        assertTrue(TvBoxAutoSites.resumeOnly(config.copy(
            spiderJar = "https://licensed.example/changed.jar;md5;different"), pin).isEmpty())
        assertTrue(TvBoxAutoSites.resumeOnly(config.copy(
            sites = config.sites.filter { it.key != "chosen" }), pin).isEmpty())
        assertTrue(TvBoxAutoSites.resumeOnly(config, "not-a-site-id").isEmpty())
    }
}
