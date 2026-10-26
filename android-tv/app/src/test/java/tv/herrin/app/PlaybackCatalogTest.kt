package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackCatalogTest {
    private fun film(playback: String): JSONObject = JSONObject(
        """{"id":"licensed-example","title":"Licensed example","playback":$playback}"""
    )
    private val valid = """{"episodes":[
      {"number":2,"title":"第二集","lines":[
        {"id":"main","title":"主线路","url":"https://media.example.test/ep2.m3u8?token=abc"}]},
      {"number":1,"title":"第一集","lines":[
        {"id":"official","title":"官方线路","url":"https://media.example.test/ep1.mp4"}]}
    ]}"""

    @Test fun parsesOrderedEpisodesAndResolvesOnlySelectedAuthorizedLine() {
        val parsed = PlaybackCatalogJson.decode(film(valid))
        assertEquals(listOf(1, 2), parsed.map { it.number })
        val title = Film("licensed-example", "Licensed example", "", "电影", "2026", "",
            artwork = 1, playbackEpisodes = parsed)
        val result = LicensedPlaybackResolver.resolve(title, 2, "main")
        assertTrue(result is PlaybackResult.Ready)
        assertEquals("https://media.example.test/ep2.m3u8?token=abc",
            (result as PlaybackResult.Ready).media.uri)
        assertTrue(LicensedPlaybackResolver.resolve(title, 2, "official") is PlaybackResult.Unavailable)
        assertTrue(LicensedPlaybackResolver.resolve(title, 99, "main") is PlaybackResult.Unavailable)
        assertTrue(LicensedPlaybackResolver.resolve(title.copy(playbackEpisodes = emptyList()), 1, "main")
            is PlaybackResult.Unavailable)
    }

    @Test fun catalogDecodesLicensedEpisodesWithoutBreakingLegacySchema() {
        val json = """{"version":1,"items":[
          {"id":"licensed-example","title":"Licensed example","playback":$valid},
          {"id":"legacy-example","title":"Legacy example","episodes":4}
        ]}"""
        val films = CatalogJson.decode(json)
        assertEquals(2, films.size)
        assertEquals(2, films.first().episodes)
        assertEquals(2, films.first().playbackEpisodes.size)
        assertEquals(4, films.last().episodes)
        assertTrue(films.last().playbackEpisodes.isEmpty())
    }

    @Test fun rejectsDuplicateEpisodesDuplicateLinesAndUnsafeHttpStreams() {
        val duplicateEpisode = """{"episodes":[
          {"number":1,"lines":[{"id":"a","url":"https://example.org/1.mp4"}]},
          {"number":1,"lines":[{"id":"b","url":"https://example.org/2.mp4"}]}
        ]}"""
        val duplicateLine = """{"episodes":[{"number":1,"lines":[
          {"id":"same","url":"https://example.org/1.mp4"},
          {"id":"same","url":"https://example.org/2.mp4"}
        ]}]}"""
        val unsafe = """{"episodes":[{"number":1,"lines":[
          {"id":"main","url":"http://example.org/video.mp4"}
        ]}]}"""
        for (input in listOf(duplicateEpisode, duplicateLine, unsafe)) {
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackCatalogJson.decode(film(input))
            }
        }
    }

    @Test fun missingPlaybackIsExplicitlyNotPlayable() {
        assertTrue(PlaybackCatalogJson.decode(JSONObject("""{"id":"legacy"}""")).isEmpty())
    }
}
