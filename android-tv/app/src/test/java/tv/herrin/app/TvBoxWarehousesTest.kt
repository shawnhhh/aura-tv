package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TvBoxWarehousesTest {
    @Test fun lastWorkingSpiderSiteIsTriedBeforeSlowCmsSites() {
        val cms = (1..9).map { TvBoxSite("CMS $it", false,
            apiUrl = "https://example.org/cms/$it", key = "cms$it") }
        val spider = TvBoxSite("影视", true, key = "movie", spiderApi = "csp_Real")
        val config = TvBoxConfig.Single(cms + spider, emptyList(), true,
            spiderJar = "https://example.org/engine.jar;md5;0123456789abcdef0123456789abcdef")
        val id = TvBoxAutoSites.identity(spider, config.spiderJar)
        val ordered = TvBoxAutoSites.candidates(config, id)
        assertEquals("movie", ordered.first().key)
        assertEquals(9, ordered.size) // The 9th CMS is outside the bounded fallback.
        assertEquals(1, ordered.count { it.key == "movie" })
        assertEquals("cms1", ordered[1].key)
    }

    @Test fun changedSpiderBinaryNeverReusesTheOldPreferenceOrTrust() {
        val spider = TvBoxSite("影视", true, key = "movie", spiderApi = "csp_Real")
        val firstJar = "https://example.org/engine.jar;md5;0123456789abcdef0123456789abcdef"
        val changedJar = "https://example.org/engine.jar;md5;fedcba9876543210fedcba9876543210"
        val cms = TvBoxSite("CMS", false, apiUrl = "https://example.org/cms", key = "cms")
        val oldId = TvBoxAutoSites.identity(spider, firstJar)
        val newConfig = TvBoxConfig.Single(listOf(cms, spider), emptyList(), true, spiderJar = changedJar)
        assertEquals(listOf("cms", "movie"), TvBoxAutoSites.candidates(newConfig, oldId).map { it.key })
        assertNotEquals(oldId, TvBoxAutoSites.identity(spider, changedJar))
        assertFalse(TvBoxSpiderPolicy.mayRun(false, "https://example.org/tv", changedJar,
            "https://example.org/tv", firstJar))
    }

    @Test fun stalePreferredSiteFallsBackToOriginalPriorityAndSkipsDuplicates() {
        val spider = TvBoxSite("片单测试", true, key = "list", spiderApi = "csp_List")
        val playable = TvBoxSite("电影站", true, key = "film", spiderApi = "csp_Film")
        val config = TvBoxConfig.Single(listOf(spider, playable), emptyList(), true)
        val candidates = TvBoxAutoSites.candidates(config, "expired-preference")
        assertEquals(listOf("film", "list"), candidates.map { it.key })
        val preferred = TvBoxAutoSites.identity(spider, config.spiderJar)
        assertEquals(listOf("list", "film"), TvBoxAutoSites.candidates(config, preferred).map { it.key })
    }

    @Test fun previouslyWorkingCmsSkipsEarlierSlowCmsOnNextLaunch() {
        val cms = (1..4).map { TvBoxSite("CMS $it", false,
            apiUrl = "https://example.org/api/$it", key = "cms$it") }
        val config = TvBoxConfig.Single(cms, emptyList(), false)
        val id = TvBoxAutoSites.identity(cms.last(), config.spiderJar)
        assertEquals(listOf("cms4", "cms1", "cms2", "cms3"),
            TvBoxAutoSites.candidates(config, id).map { it.key })
    }

    @Test fun spiderOnlyAutoRunsAfterApplyOrWithExactPreviouslyTrustedJar() {
        val source = "http://www.饭太硬.net/tv"
        val oldJar = "https://example.org/spider.jar;md5;0123456789abcdef0123456789abcdef"
        val newJar = "https://example.org/spider.jar;md5;fedcba9876543210fedcba9876543210"
        assertTrue(TvBoxSpiderPolicy.mayRun(true, source, newJar, "", ""))
        assertTrue(TvBoxSpiderPolicy.mayRun(false, source, oldJar, source, oldJar))
        assertFalse(TvBoxSpiderPolicy.mayRun(false, source, newJar, source, oldJar))
        assertFalse(TvBoxSpiderPolicy.mayRun(false, "https://another.example/tv", oldJar, source, oldJar))
        assertFalse(TvBoxSpiderPolicy.mayRun(false, source, oldJar, "", ""))
    }

    @Test fun acceptsChineseIdnAndRejectsNonNetworkAndCredentialUrls() {
        assertEquals("http://www.xn--sss604efuw.net/tv", TvBoxUrl.normalize("http://www.饭太硬.net/tv", true))
        assertNull(TvBoxUrl.normalize("http://www.饭太硬.net/tv"))
        assertEquals("https://example.com/catalog.json", TvBoxUrl.normalize("HTTPS://EXAMPLE.COM/catalog.json"))
        for (bad in listOf("file:///tmp/config", "javascript:alert(1)", "https://u:p@example.org/a",
                "https://example.org/#fragment", "https://example.org:invalid/a", "https://bad.example/a\\b")) {
            assertNull("unsafe URL $bad", TvBoxUrl.normalize(bad, true))
        }
    }

    @Test fun parsesMultiwarehouseAndSkipsInvalidDuplicates() {
        val config = TvBoxConfigParser.parse("""{
          "urls": [
            {"name":"饭太硬", "url":"http://www.饭太硬.net/tv"},
            {"name":"测试仓", "url":"https://media.example.org/box.json"},
            {"name":"重复", "url":"https://media.example.org/box.json"},
            {"name":"危险文件", "url":"file:///sdcard/secret"},
            {"name":"空地址"}
          ]
        }""", "https://example.org/root") as TvBoxConfig.Warehouses
        assertEquals(2, config.entries.size)
        assertEquals(3, config.ignored)
        assertEquals("饭太硬", config.entries.first().name)
        assertTrue(config.entries.first().url.startsWith("http://www.xn--"))
    }

    @Test fun parsesSingleWarehouseWithoutExecutingExternalEngines() {
        val config = TvBoxConfigParser.parse("""{
          "spider":"https://evil.example.org/engine.jar",
          "sites": [{"key":"news", "name":"影视站", "api":"csp_A"}, {"name":"公开演示"}],
          "lives": [{"name":"我授权的直播", "url":"https://mine.example.org/tv.m3u"},
                    {"name":"不安全 HTTP", "url":"http://untrusted.example.org/tv.m3u"},
                    {"name":"聚合文本", "url":"https://other.example.org/live.txt"}]
        }""", "https://example.org/one") as TvBoxConfig.Single
        assertEquals(2, config.sites.size)
        assertTrue(config.hasExternalEngine)
        assertEquals("https://mine.example.org/tv.m3u", config.lives[0].playlistUrl)
        assertNull(config.lives[1].playlistUrl)
        assertNull(config.lives[2].playlistUrl)
    }

    @Test fun drpyJavascriptRulesAreNotMisidentifiedAsCmsCatalogs() {
        val config = TvBoxConfigParser.parse("""{"sites":[
            {"name":"JS站","api":"https://example.org/drpy2.min.js"},
            {"name":"CMS站","api":"http://example.org/api.php"},
            {"name":"爬虫站","api":"csp_Guard"}
        ]}""", "https://example.org") as TvBoxConfig.Single
        assertEquals("https://example.org/drpy2.min.js", config.sites[0].scriptApi)
        assertNull(config.sites[0].apiUrl)
        assertTrue(config.sites[0].needsAdapter)
        assertEquals("http://example.org/api.php", config.sites[1].apiUrl)
        assertEquals("csp_Guard", config.sites[2].spiderApi)
    }

    @Test fun recognizesHerrinCatalogButDoesNotConfuseOtherFormats() {
        val own = TvBoxConfigParser.parse("""{"version":1,"items":[{},{}]}""", "https://mine.example.org/catalog")
        assertEquals(2, (own as TvBoxConfig.HerrinCatalog).count)
        assertTrue(TvBoxConfigParser.parse("<html>blocked</html>", "https://example.org") is TvBoxConfig.Error)
        assertTrue(TvBoxConfigParser.parse("{}", "https://example.org") is TvBoxConfig.Error)
        assertTrue(TvBoxConfigParser.parse("""{"urls":"bad"}""", "https://example.org") is TvBoxConfig.Error)
    }

    @Test fun limitsEntriesToOneHundredAndTruncatesNames() {
        val body = (0 until 120).joinToString(",") { """{"name":"${"很".repeat(80)}", "url":"https://example.org/$it"}""" }
        val result = TvBoxConfigParser.parse("""{"urls":[$body]}""", "https://example.org") as TvBoxConfig.Warehouses
        assertEquals(100, result.entries.size)
        assertEquals(20, result.ignored)
        assertEquals(48, result.entries.first().name.length)
    }
}
