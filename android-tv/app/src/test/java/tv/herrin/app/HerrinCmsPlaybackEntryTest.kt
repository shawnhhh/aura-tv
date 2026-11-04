package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Catches double parsing, lost selected lines, and forwarding API credentials to media. */
class HerrinCmsPlaybackEntryTest {
    @Test fun selectedSourceFailureCannotFallThroughToIndependentTestMedia() {
        val sourceFilm = Film("configured", "Film", "", "电影", "2026", "", 0,
            playbackEpisodes = listOf(PlaybackEpisode(1, "First", listOf(
                PlaybackLine("selected", "Selected", "https://media.example/film.mp4")))))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm, false, false,
            "https://test.example/unrelated.mp4"))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm.copy(id = "tmdb-1",
            playbackEpisodes = emptyList()), false, false, "https://test.example/unrelated.mp4"))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm.copy(playbackEpisodes = emptyList(),
            cmsVodId = "cms-original"), false, false, "https://test.example/unrelated.mp4"))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm.copy(playbackEpisodes = emptyList(),
            spiderVodId = "spider-original"), false, false, "https://test.example/unrelated.mp4"))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm.copy(playbackEpisodes = emptyList()),
            true, false, "https://test.example/unrelated.mp4"))
        assertNull(HerrinPlaybackEntry.independentTestMedia(sourceFilm.copy(playbackEpisodes = emptyList()),
            false, true, "https://test.example/unrelated.mp4"))
    }

    @Test fun independentTestRemainsAvailableForOfflineDemonstration() {
        val demo = Film("demo", "Demo", "", "电影", "2026", "", 0)
        assertEquals("https://test.example/fixture.mp4", HerrinPlaybackEntry.independentTestMedia(
            demo, false, false, "https://test.example/fixture.mp4")?.uri)
    }

    @Test fun htmlRouteNamesOneConfiguredNativeParser() {
        val spec = HerrinPlaybackEntry.cmsContent(
            PlaybackLine("second", "VIP", "https://watch.example/film.html"),
            listOf(TvBoxJsonParse("Configured JSON", "https://parser.example/?url=")),
            listOf(TvBoxPlaybackParser("Browser", 0, "https://browser.example/?url=")),
            mapOf("Authorization" to "secret", "Cookie" to "secret", "User-Agent" to "Herrin", "Referer" to "https://watch.example/"))
        val content = JSONObject(spec.playerContent)
        assertEquals("https://watch.example/film.html", content.getString("url"))
        assertEquals("parse:Configured JSON", content.getString("playUrl"))
        assertEquals(1, content.getInt("parse"))
        assertEquals(mapOf("User-Agent" to "Herrin", "Referer" to "https://watch.example/"), spec.headers)
    }

    @Test fun signedExtensionlessStreamWithoutFlagIntentStaysDirect() {
        val spec = HerrinPlaybackEntry.cmsContent(
            PlaybackLine("line", "direct", "https://media.example/stream?signature=fixture"),
            listOf(TvBoxJsonParse("JSON", "https://parser.example/?url=")),
            listOf(TvBoxPlaybackParser("Browser", 0, "https://browser.example/?url=")))
        val content = JSONObject(spec.playerContent)
        assertEquals(0, content.getInt("parse"))
        assertEquals("", content.getString("playUrl"))
        assertEquals("https://media.example/stream?signature=fixture", content.getString("url"))
    }

    @Test fun explicitFlagSelectsNativeParserForExtensionlessWatchRoute() {
        val spec = HerrinPlaybackEntry.cmsContent(
            PlaybackLine("line", "VIP", "https://watch.example/film"), emptyList(),
            listOf(TvBoxPlaybackParser("Named JSON", 1, "https://parser.example/?url=", listOf("vip"))))
        val content = JSONObject(spec.playerContent)
        assertEquals("parse:Named JSON", content.getString("playUrl"))
        assertEquals(1, content.getInt("parse"))
    }

    @Test fun mediaExtensionWinsOverFlagAndPreservesSidecars() {
        val selected = PlaybackLine("line", "VIP", "https://media.example/film.mp4?token=fixture",
            listOf(AuthorizedSubtitle("en", "English", "https://media.example/sub.vtt")))
        val spec = HerrinPlaybackEntry.cmsContent(selected,
            listOf(TvBoxJsonParse("JSON", "https://parser.example/?url=", listOf("VIP"))), emptyList())
        val content = JSONObject(spec.playerContent)
        assertEquals(0, content.getInt("parse"))
        assertEquals("", content.getString("playUrl"))
        assertEquals(selected.subtitles, spec.subtitles)
    }

    @Test fun unconfiguredHtmlStillRequestsNativeBrowserDetection() {
        val content = JSONObject(HerrinPlaybackEntry.cmsContent(
            PlaybackLine("line", "web", "https://watch.example/film.htm"), emptyList(), emptyList()).playerContent)
        assertEquals(1, content.getInt("parse"))
        assertEquals("", content.getString("playUrl"))
    }
}
