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

    @Test fun refusesUnknownOrMalformedCmsResponses() {
        assertThrows(IllegalStateException::class.java) { TvBoxVodCodec.decode("{}") }
        assertTrue(TvBoxVodCodec.decode("""{"list":[{"vod_name":"缺少编号"}]}""").isEmpty())
    }
}
