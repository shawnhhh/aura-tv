package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class TvBoxSpiderBridgeTest {
    private val site = TvBoxSite("测试站", true, key = "video", spiderApi = "csp_Demo")

    @Test fun `reflection errors surface the actual failing Spider dependency`() {
        val nested = InvocationTargetException(IllegalStateException("guard initialization unavailable"))
        assertEquals("IllegalStateException：guard initialization unavailable",
            SpiderFailureDetails.message(nested))
    }

    @Test fun `site metadata retains external jar and extension without execution`() {
        val parsed = TvBoxConfigParser.parse("""
            {"spider":"https://example.org/plugin.jpg;md5;0123456789abcdef0123456789abcdef",
             "sites":[{"key":"video","name":"测试站","type":3,"api":"csp_Demo",
             "ext":{"siteUrl":"https://example.org/"}}]}
        """.trimIndent(), "https://example.org/tv") as TvBoxConfig.Single
        assertEquals("csp_Demo", parsed.sites.single().spiderApi)
        assertEquals("video", parsed.sites.single().key)
        assertTrue(parsed.sites.single().extension.contains("siteUrl"))
        assertTrue(parsed.spiderJar!!.contains(";md5;"))
    }

    @Test fun `spider home becomes stable film with opaque vod id`() {
        val film = SpiderVodCodec.listing("""{"list":[{"vod_id":"ID?x=1&y=2", "vod_name":"影集"}]}""", site).single()
        assertEquals("ID?x=1&y=2", film.spiderVodId)
        assertEquals("影集", film.title)
        assertTrue(film.playbackEpisodes.isEmpty())
    }

    @Test fun `spider detail preserves opaque identifiers rather than rejecting non HTTP`() {
        val film = SpiderVodCodec.listing("""{"list":[{"vod_id":"37","vod_name":"影片"}]}""", site).single()
        val detailed = SpiderVodCodec.detail("""{"list":[{"vod_play_from":"A${'$'}${'$'}${'$'}B",
             "vod_play_url":"第一集${'$'}token-A#第二集${'$'}token-B${'$'}${'$'}${'$'}正片${'$'}opaque-id"}]}""", film)
        assertEquals(2, detailed.playbackEpisodes.size)
        assertEquals("token-A", detailed.playbackEpisodes[0].lines[0].uri)
        assertEquals("opaque-id", detailed.playbackEpisodes[0].lines[1].uri)
        assertEquals("B", detailed.playbackEpisodes[0].lines[1].title)
    }

    @Test fun `player result accepts media url and bounded playback headers`() {
        val response = SpiderVodCodec.player("""{"parse":0,"url":"https://video.example/movie.m3u8",
          "header":{"Referer":"https://example.org/","User-Agent":"Test","Bad Header":"discard"}}""", false)
        assertTrue(response is PlaybackResult.Ready)
        val media = (response as PlaybackResult.Ready).media
        assertEquals("https://video.example/movie.m3u8", media.uri)
        assertEquals("https://example.org/", media.headers["Referer"])
        assertFalse(media.headers.containsKey("Bad Header"))
    }

    @Test fun `player result cannot silently pass web page or http without consent`() {
        assertTrue(SpiderVodCodec.player("""{"parse":1,"url":"https://example.org/watch"}""", false) is PlaybackResult.Unavailable)
        assertTrue(SpiderVodCodec.player("""{"parse":0,"url":"http://example.org/film.mp4"}""", false) is PlaybackResult.Unavailable)
        assertTrue(SpiderVodCodec.player("""{"parse":0,"url":"http://example.org/film.mp4"}""", true) is PlaybackResult.Ready)
    }

    @Test fun `catalog fallback visits film categories after empty home and failed category`() {
        val visited = mutableListOf<String>()
        val films = SpiderCatalogBrowser.browse(site,
            home = { """{"class":[{"type_id":"1","type_name":"片单"},
                {"type_id":"2","type_name":"电视剧"},{"type_id":"3","type_name":"电影"}]}""" },
            homeVideos = { "" }, category = { id ->
                visited += id
                if (id == "2") throw IllegalStateException("分类暂不可用")
                if (id == "3") """{"list":[{"vod_id":"film-3","vod_name":"真实目录条目"}]}"""
                else """{"list":[]}"""
            })
        assertEquals(listOf("2", "3"), visited)
        assertEquals("film-3", films.single().spiderVodId)
    }

    @Test fun `catalog fallback survives first home exception`() {
        val films = SpiderCatalogBrowser.browse(site,
            home = { filter ->
                if (filter) throw IllegalArgumentException("home filter unsupported")
                """{"list":[{"vod_id":"backup","vod_name":"备用首页"}]}"""
            }, homeVideos = { "" }, category = { error("not needed") })
        assertEquals("备用首页", films.single().title)
    }

    @Test fun `native guard filename matches ABI specific library name`() {
        assertEquals("assets/ftyguard_v8.so", SpiderNativeLibraryPlan.assetForAbi("arm64-v8a"))
        assertEquals("assets/ftyguard_v7.so", SpiderNativeLibraryPlan.assetForAbi("armeabi-v7a"))
        assertEquals(null, SpiderNativeLibraryPlan.assetForAbi("x86_64"))
        assertEquals("libftyguard_v8.so",
            SpiderNativeLibraryPlan.filenameForAsset("assets/ftyguard_v8.so"))
    }
}
