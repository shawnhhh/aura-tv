package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** TVBox CMS list/detail parity for nodes which expose episodes only on ac=detail&ids=… . */
class M420TvBoxCmsDetailTest {
    @Test fun tvboxRootVipFlagsAreAvailableToSpiderPlayerAndRemainBounded() {
        val flags = JSONArray().put("  腾讯  ").put("爱奇艺").put("腾讯")
        repeat(40) { flags.put("custom-$it") }
        val root = JSONObject().put("sites", JSONArray().put(JSONObject()
            .put("key", "custom-site").put("name", "自选插件")
            .put("api", "csp_Custom"))).put("flags", flags)
        val parsed = TvBoxConfigParser.parse(root.toString(), "https://licensed.example.test/config.json")
            as TvBoxConfig.Single
        assertEquals("腾讯", parsed.vipFlags.first())
        assertEquals("爱奇艺", parsed.vipFlags[1])
        assertEquals(32, parsed.vipFlags.size)
        assertEquals(1, parsed.vipFlags.count { it == "腾讯" })
    }

    @Test fun spiderDetailKeepsMoreThanNinetyNineEpisodesAndEightPlayableLines() {
        val site = TvBoxSite("自选 Spider", needsAdapter = true, key = "spider",
            spiderApi = "csp_Authorized")
        val listed = SpiderVodCodec.listing("""{"list":[{
            "vod_id":"own-serial","vod_name":"授权系列"
        }]}""", site).single()
        val lineFlags = (1..9).joinToString("${'$'}${'$'}${'$'}") { "线路 $it" }
        val playlist = (1..110).joinToString("#") { number ->
            "第${number}集${'$'}https://licensed.example.test/serial/${number}.m3u8"
        }
        val detail = JSONObject().put("list", JSONArray().put(JSONObject()
            .put("vod_play_from", lineFlags)
            .put("vod_play_url", (1..9).joinToString("${'$'}${'$'}${'$'}") { playlist })))
        val result = SpiderVodCodec.detail(detail.toString(), listed)
        assertEquals(110, result.playbackEpisodes.size)
        assertEquals("第110集", result.playbackEpisodes.last().title)
        assertEquals(9, result.playbackEpisodes.first().lines.size)
        assertEquals("线路 9", result.playbackEpisodes.first().lines.last().title)
    }

    @Test fun spiderPlayerUsesConfiguredSiteHeadersUnlessResponseOverridesThem() {
        val config = TvBoxConfigParser.parse("""{
            "sites":[{"key":"spider","name":"自选测试","type":3,"api":"csp_Custom",
              "header":{"Referer":"https://licensed.example.test/",
                "User-Agent":"Authorized-Test-UA"}}]
        }""", "https://licensed.example.test/config") as TvBoxConfig.Single
        val siteHeaders = config.sites.single().headers
        assertEquals("Authorized-Test-UA", siteHeaders["User-Agent"])
        val withoutHeaders = SpiderVodCodec.player(
            """{"parse":0,"url":"https://media.example.test/original.m3u8"}""",
            allowHttp = false, siteHeaders = siteHeaders) as PlaybackResult.Ready
        assertEquals(siteHeaders, withoutHeaders.media.headers)

        val withSourceOverride = SpiderVodCodec.player("""{
            "parse":0,"url":"https://media.example.test/original.m3u8",
            "header":{"Referer":"https://override.example.test/"}
        }""", allowHttp = false, siteHeaders = siteHeaders) as PlaybackResult.Ready
        assertEquals("https://override.example.test/", withSourceOverride.media.headers["Referer"])
        assertFalse("Provider-specified header object replaces site defaults",
            withSourceOverride.media.headers.containsKey("User-Agent"))
    }

    @Test fun listWithoutPlaybackMustRemainSelectableUntilDetailIsFetched() {
        val catalog = TvBoxVodCodec.decode("""{
            "list":[{"vod_id":"season/42?x=1&y=2","vod_name":"本地授权剧集",
                "vod_pic":"https://licensed.example.test/poster.jpg"}]
        }""")
        val listed = catalog.single()
        assertEquals("season/42?x=1&y=2", listed.cmsVodId)
        assertTrue(listed.playbackEpisodes.isEmpty())
        val detailed = TvBoxVodCodec.detail("""{"list":[{
            "vod_id":"season/42?x=1&y=2", "vod_name":"本地授权剧集",
            "vod_play_from":"主线${'$'}${'$'}${'$'}备用",
            "vod_play_url":"第01集${'$'}https://licensed.example.test/01.m3u8#第02集${'$'}https://licensed.example.test/02.m3u8${'$'}${'$'}${'$'}第01集${'$'}https://licensed.example.test/b01.m3u8"
        }]}""", listed, allowHttp = false)
        assertEquals(listed.id, detailed.id)
        assertEquals(2, detailed.playbackEpisodes.size)
        assertEquals("第01集", detailed.playbackEpisodes[0].title)
        assertEquals(listOf("主线", "备用"), detailed.playbackEpisodes[0].lines.map { it.title })
        assertEquals(1, detailed.playbackEpisodes[1].lines.size)
        assertTrue(LicensedPlaybackResolver.resolve(detailed, 2, "cms-0") is PlaybackResult.Ready)
    }

    @Test fun detailAddressPreservesSignedCmsQueryAndSafelyEncodesOpaqueId() {
        val endpoint = TvBoxCmsEndpoint.address(
            "https://licensed.example.test/api.php?token=a%2Fb&ac=list&pg=3",
            vodId = "season/42?x=1&y=2", allowHttp = false)
        assertNotNull(endpoint)
        assertTrue(endpoint!!.contains("token=a%2Fb"))
        assertFalse("Signed token must not be percent-escaped twice", endpoint.contains("a%252Fb"))
        assertTrue(endpoint.contains("ac=detail"))
        assertTrue(endpoint.contains("ids=season%2F42%3Fx%3D1%26y%3D2"))
        assertFalse(endpoint.contains("pg=3"))
    }

    @Test fun namedAndBareDirectPlaybackEntriesBothProduceEpisodes() {
        val listed = TvBoxVodCodec.decode("""{"list":[{"vod_id":"direct","vod_name":"自制测试片"}]}""")
            .single()
        val detailed = TvBoxVodCodec.detail("""{"list":[{
            "vod_id":"direct","vod_name":"自制测试片",
            "vod_play_from":"直链",
            "vod_play_url":"https://licensed.example.test/original.mp4"
        }]}""", listed, allowHttp = false)
        assertEquals("https://licensed.example.test/original.mp4",
            detailed.playbackEpisodes.single().lines.single().uri)
        assertTrue(LicensedPlaybackResolver.resolve(detailed, 1, "cms-0") is PlaybackResult.Ready)
    }

    @Test fun invalidPlaylistMustNotBecomePlayableBrowserPage() {
        val listed = TvBoxVodCodec.decode("""{"list":[{"vod_id":"web","vod_name":"网页片段"}]}""")
            .single()
        val result = TvBoxVodCodec.detail("""{"list":[{
            "vod_id":"web","vod_name":"网页片段",
            "vod_play_url":"网页${'$'}javascript:location.href='https://elsewhere/'"
        }]}""", listed, allowHttp = false)
        assertTrue(result.playbackEpisodes.isEmpty())
    }
}
