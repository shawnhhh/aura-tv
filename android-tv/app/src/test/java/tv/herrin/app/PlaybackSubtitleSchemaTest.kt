package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/** Version-1 catalog caption metadata is optional and never grants content rights. */
class PlaybackSubtitleSchemaTest {
    private val licensed = """{
      "version":1,
      "items":[{
        "id":"demo","title":"已授权的测试片段","category":"电影",
        "playback":{"episodes":[{"number":1,"lines":[{
          "id":"official","title":"主线路",
          "url":"https://licensed.example.org/play.m3u8",
          "subtitles":[
            {"language":"zh-CN","title":"简体中文","url":"https://licensed.example.org/zh.vtt?signature=x"},
            {"language":"en","title":"English","url":"https://licensed.example.org/en.vtt"}
          ]
        }]}]}
      }]
    }"""

    @Test fun authorizedEpisodeCarriesWebVttSubtitleTracksToMedia3Request() {
        val film = CatalogJson.decode(licensed).single()
        val result = LicensedPlaybackResolver.resolve(film, 1, "official")
        assertTrue(result is PlaybackResult.Ready)
        val media = (result as PlaybackResult.Ready).media
        assertEquals(2, media.subtitles.size)
        assertEquals("zh-cn", media.subtitles[0].language)
        assertEquals("简体中文", media.subtitles[0].title)
        assertEquals("https://licensed.example.org/zh.vtt?signature=x", media.subtitles[0].uri)
        assertEquals("en", media.subtitles[1].language)
    }

    @Test fun rejectDuplicateSubtitleLanguagesAndUntrustedCaptionUrls() {
        val duplicate = licensed.replace("\"language\":\"en\"", "\"language\":\"zh-CN\"")
        val unsafe = licensed.replace("https://licensed.example.org/en.vtt",
            "http://licensed.example.org/en.vtt")
        val credentials = licensed.replace("https://licensed.example.org/en.vtt",
            "https://alice:secret@licensed.example.org/en.vtt")
        for (payload in listOf(duplicate, unsafe, credentials)) {
            assertThrows(IllegalArgumentException::class.java) { CatalogJson.decode(payload) }
        }
    }

    @Test fun legacyCatalogWithoutCaptionConfigRemainsCompatible() {
        val source = """{"version":1,"items":[{"id":"legacy","title":"测试"}]}"""
        val film = CatalogJson.decode(source).single()
        assertTrue(film.playbackEpisodes.isEmpty())
        assertTrue(LicensedPlaybackResolver.resolve(film, 1, "official") is PlaybackResult.Unavailable)
    }
}
