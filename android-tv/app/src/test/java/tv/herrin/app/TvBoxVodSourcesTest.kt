package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TvBoxVodSourcesTest {
    @Test fun cmsJsonListsFilmsAndHttpsPlaybackEpisodes() {
        val result = TvBoxVodCodec.decode("""{
          "code":1,"list":[{
            "vod_id":110,"vod_name":"我自己的授权短片","type_name":"电影",
            "vod_remarks":"1080p","vod_pic":"https://example.org/poster.jpg",
            "vod_year":"2026","vod_content":"<p>一部测试短片</p>",
            "vod_play_from":"官方${'$'}${'$'}${'$'}备用",
            "vod_play_url":"第1集${'$'}https://example.org/a.m3u8#第2集${'$'}https://example.org/b.m3u8${'$'}${'$'}${'$'}第1集${'$'}javascript:alert(1)"
          }]
        }""")
        assertEquals(1, result.size)
        val film = result.single()
        assertEquals("我自己的授权短片", film.title)
        assertEquals("一部测试短片", film.synopsis.trim())
        assertEquals(2, film.playbackEpisodes.size)
        assertEquals("https://example.org/a.m3u8", film.playbackEpisodes.first().lines.single().uri)
        assertTrue(LicensedPlaybackResolver.resolve(film, 1, "cms-0") is PlaybackResult.Ready)
    }


    @Test fun cmsHttpPlaybackIsPlayableOnlyWhenUserEnabledHttp() {
        val dollar = 36.toChar()
        val json = """{"list":[{"vod_id":"42","vod_name":"普通非网盘测试影片",
            "vod_play_from":"官方",
            "vod_play_url":"正片${dollar}http://media.example.org/stream?id=42"}]}"""
        val film = TvBoxVodCodec.decode(json, allowHttp = true).single()
        assertTrue(TvBoxCmsPlaybackResolver.resolve(film, 1, "cms-0", false)
            is PlaybackResult.Unavailable)
        val allowed = TvBoxCmsPlaybackResolver.resolve(film, 1, "cms-0", true,
            mapOf("User-Agent" to "HerrinTest", "Referer" to "https://source.test/",
                "Cookie" to "secret", "Authorization" to "Bearer PRIVATE"))
        assertTrue(allowed is PlaybackResult.Ready)
        val media = (allowed as PlaybackResult.Ready).media
        assertEquals("http://media.example.org/stream?id=42", media.uri)
        assertTrue(media.allowHttpRedirects)
        assertEquals("HerrinTest", media.headers["User-Agent"])
        assertFalse(media.headers.containsKey("Cookie"))
        assertFalse(media.headers.containsKey("Authorization"))
        assertTrue(TvBoxCmsPlaybackResolver.resolve(film, 1, "cms-99", true)
            is PlaybackResult.Unavailable)
    }

    @Test fun cmsHttpsAndWebpagePlaybackAreDistinguished() {
        val dollar = 36.toChar()
        val film = TvBoxVodCodec.decode("""{"list":[{
            "vod_id":"50","vod_name":"官方测试 HLS",
            "vod_play_url":"第一集${dollar}https://cdn.example.org/stream.m3u8?sig=abc"
        }]}""").single()
        val result = TvBoxCmsPlaybackResolver.resolve(film, 1, "cms-0", false)
        assertEquals("https://cdn.example.org/stream.m3u8?sig=abc",
            (result as PlaybackResult.Ready).media.uri)
        val webpage = film.copy(playbackEpisodes = listOf(PlaybackEpisode(1, "第一集",
            listOf(PlaybackLine("cms-0", "网页解析", "https://jx.example.org/watch.html")))))
        assertTrue(TvBoxCmsPlaybackResolver.resolve(webpage, 1, "cms-0", true)
            is PlaybackResult.Unavailable)
    }

    @Test fun refusesUnknownOrMalformedCmsResponses() {
        assertThrows(IllegalStateException::class.java) { TvBoxVodCodec.decode("{}") }
        assertTrue(TvBoxVodCodec.decode("""{"list":[{"vod_name":"缺少编号"}]}""").isEmpty())
    }

    @Test fun userAllowedHttpAlsoAppliesToPostersAndPlayback() {
        val json = """{"list":[{"vod_id":"42","vod_name":"家庭测试片",
            "vod_pic":"http://example.org/poster.jpg",
            "vod_play_url":"正片${'$'}http://example.org/movie.m3u8"}]}"""
        val strict = TvBoxVodCodec.decode(json)
        assertTrue(strict.single().playbackEpisodes.isEmpty())
        val allowed = TvBoxVodCodec.decode(json, allowHttp = true).single()
        assertEquals("http://example.org/poster.jpg", allowed.artworkUrl)
        assertEquals("http://example.org/movie.m3u8", allowed.playbackEpisodes.single().lines.single().uri)
    }
}
