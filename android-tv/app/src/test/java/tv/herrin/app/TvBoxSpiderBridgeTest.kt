package tv.herrin.app

import com.github.catvod.utils.RuntimeModule
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

    @Test fun `optional init follows reference loader but still requires installed runtimes`() {
        val harmless = InvocationTargetException(IllegalStateException("optional bootstrap unavailable"))
        assertEquals(null, SpiderInitPolicy.blocking(harmless))
        val missing = RuntimeModule.MissingException(RuntimeModule.QUICKJS)
        assertEquals(missing, SpiderInitPolicy.blocking(InvocationTargetException(missing)))
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
        assertEquals("第一集", detailed.playbackEpisodes[0].title)
        assertEquals("第二集", detailed.playbackEpisodes[1].title)
        assertEquals("opaque-id", detailed.playbackEpisodes[0].lines[1].uri)
        assertEquals("B", detailed.playbackEpisodes[0].lines[1].title)
    }

    @Test fun `spider detail enriches missing poster and metadata without changing opaque identifiers`() {
        val original = SpiderVodCodec.listing("""{"list":[{"vod_id":"opaque-3","vod_name":"真实影视"}]}""", site).single()
        assertEquals(null, original.artworkUrl)
        val enriched = SpiderVodCodec.detail("""{"list":[{
            "vod_pic":"https://images.example.org/actual-cover.webp",
            "vod_year":"2026", "vod_remarks":"更新至十二集", "type_name":"电视剧",
            "vod_play_from":"原画", "vod_play_url":"第一集${'$'}token?sig=a${'$'}b#第二集${'$'}token-2"
        }]}""", original)
        assertEquals("opaque-3", enriched.spiderVodId)
        assertEquals("https://images.example.org/actual-cover.webp", enriched.artworkUrl)
        assertEquals("2026", enriched.year)
        assertEquals("更新至十二集", enriched.subtitle)
        assertEquals("剧集", enriched.category)
        assertEquals("第一集", enriched.playbackEpisodes[0].title)
        assertEquals("token?sig=a${'$'}b", enriched.playbackEpisodes[0].lines.single().uri)
        assertEquals("第二集", enriched.playbackEpisodes[1].title)
    }

    @Test fun `invalid detail artwork cannot replace a verified listing poster`() {
        val original = SpiderVodCodec.listing("""{"list":[{
            "vod_id":"1", "vod_name":"影片", "vod_pic":"https://images.example.org/original.jpg"
        }]}""", site).single()
        val enriched = SpiderVodCodec.detail("""{"list":[{
            "vod_pic":"javascript:alert(1)", "vod_play_url":"only-opaque-id"
        }]}""", original)
        assertEquals(original.artworkUrl, enriched.artworkUrl)
        assertEquals("第 1 集", enriched.playbackEpisodes.single().title)
        assertEquals("only-opaque-id", enriched.playbackEpisodes.single().lines.single().uri)
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

    @Test fun `player supports CatVod quality pairs and WebVTT sidecar subtitles`() {
        val result = SpiderVodCodec.player("""{
            "parse":0,
            "url":["不支持","javascript:alert(1)","高清","https://media.example.org/hi.m3u8"],
            "subs":[
              {"url":"https://media.example.org/zh.vtt","name":"简体中文","lang":"zh-CN","format":"text/vtt"},
              {"url":"https://media.example.org/sub.srt","name":"不支持格式","lang":"en","format":"application/x-subrip"},
              {"url":"file:///private.vtt","name":"本地不可用","lang":"en","format":"text/vtt"}
            ]
        }""", false)
        assertTrue(result is PlaybackResult.Ready)
        val media = (result as PlaybackResult.Ready).media
        assertEquals("https://media.example.org/hi.m3u8", media.uri)
        assertEquals(1, media.subtitles.size)
        assertEquals("zh-cn", media.subtitles.single().language)
        assertEquals("简体中文", media.subtitles.single().title)
        val acrossProcess = SpiderIpc.readPlayback(SpiderIpc.playback(media), false)
        assertEquals(media, (acrossProcess as PlaybackResult.Ready).media)
    }

    @Test fun `player selects preferred URL object quality with safe fallback`() {
        val chosen = SpiderVodCodec.player("""{
            "parse":0, "url":{"values":[
                {"n":"低画质","v":"http://video.example/low.m3u8"},
                {"n":"高清","v":"https://video.example/hd.m3u8"}
            ],"position":0}
        }""", false)
        assertEquals("https://video.example/hd.m3u8", (chosen as PlaybackResult.Ready).media.uri)
        val withHttp = SpiderVodCodec.player("""{
            "url":{"values":[
                {"n":"低画质","v":"http://video.example/low.m3u8"},
                {"n":"高清","v":"https://video.example/hd.m3u8"}
            ],"position":0}
        }""", true)
        assertEquals("http://video.example/low.m3u8", (withHttp as PlaybackResult.Ready).media.uri)
    }

    @Test fun `browser parsing flags block web parse even with direct-looking URL`() {
        assertTrue(SpiderVodCodec.player("""{"jx":1,"url":"https://example.org/watch"}""", false)
            is PlaybackResult.Unavailable)
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
