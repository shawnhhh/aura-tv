package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TvBoxJsonPlaybackTest {
    @Test fun tvboxConfigKeepsOnlyExplicitType1JsonParseEndpoints() {
        val cfg = TvBoxConfigParser.parse("""{
            "sites":[{"key":"cms","name":"官方 CMS","api":"https://example.org/api.php"}],
            "parses":[
                {"name":"网页解析","type":0,"url":"https://browser.test/?url="},
                {"name":"授权 JSON","type":1,"url":"https://parser.test/parse?url=",
                 "ext":{"flag":["官方线路"],"header":{"User-Agent":"TVBoxTest"}}},
                {"name":"JAR","type":2,"url":"https://parser.test/plugin"}
            ]
        }""", "https://example.org/root") as TvBoxConfig.Single
        assertEquals(1, cfg.jsonParsers.size)
        assertEquals("授权 JSON", cfg.jsonParsers.single().name)
        assertEquals(listOf("官方线路"), cfg.jsonParsers.single().flags)
        assertEquals("TVBoxTest", cfg.jsonParsers.single().headers["User-Agent"])
    }

    @Test fun matchingFlagParsesWatchPageButDirectMediaNeverGoesToExternalParser() {
        val parser = TvBoxJsonParse("JSON", "https://parser.test/api?url=", listOf("官方线路"))
        val url = "https://watch.example.test/view?id=42&session=a/b"
        assertEquals(parser, TvBoxJsonPlayback.choose(url, "官方线路", listOf(parser)))
        assertNull(TvBoxJsonPlayback.choose(url, "其它线路", listOf(parser)))
        assertNull(TvBoxJsonPlayback.choose("https://watch.example.test/movie.m3u8",
            "官方线路", listOf(parser)))
        assertNull(TvBoxJsonPlayback.choose("javascript:alert(1)", "官方线路", listOf(parser)))
        assertEquals("https://parser.test/api?url=https%3A%2F%2Fwatch.example.test%2Fview%3Fid%3D42%26session%3Da%2Fb",
            TvBoxJsonPlayback.parserUrl(parser, url, false))
    }

    @Test fun unflaggedParserOnlyAcceptsObviousHtmlWatchPage() {
        val parser = TvBoxJsonParse("JSON", "https://parser.test/api?url=")
        assertEquals(parser, TvBoxJsonPlayback.choose("https://watch.test/film.html", "备用",
            listOf(parser)))
        assertNull(TvBoxJsonPlayback.choose("https://watch.test/secure?id=12",
            "备用", listOf(parser)))
    }

    @Test fun parsesNestedJsonMediaWithMimeAndWithoutLeakingTokensAcrossHosts() {
        val parser = TvBoxJsonParse("JSON", "https://parser.test/?url=")
        val result = TvBoxJsonPlayback.decode("""{
            "code":200,"data":{"url":"https://cdn.test/stream?id=42",
            "format":"application/vnd.apple.mpegurl",
            "header":{"Referer":"https://watch.test/","Cookie":"SECRET"}}}
        """, parser, false)
        assertTrue(result is PlaybackResult.Ready)
        val media = (result as PlaybackResult.Ready).media
        assertEquals("https://cdn.test/stream?id=42", media.uri)
        assertEquals(PlaybackFormatRetry.HLS, media.declaredMime)
        assertEquals("https://watch.test/", media.headers["Referer"])
        assertFalse(media.headers.containsKey("Cookie"))
        assertFalse(media.headers.containsKey("Authorization"))
    }

    @Test fun parserRejectsWebpagesGarbageAndUnapprovedCleartext() {
        val parser = TvBoxJsonParse("JSON", "https://parser.test/api?url=")
        assertTrue(TvBoxJsonPlayback.decode("bad json", parser, false)
            is PlaybackResult.Unavailable)
        assertTrue(TvBoxJsonPlayback.decode("""{"url":"https://watch.test/index.html"}""",
            parser, false) is PlaybackResult.Unavailable)
        assertTrue(TvBoxJsonPlayback.decode("""{"url":"http://cdn.test/film.mp4"}""",
            parser, false) is PlaybackResult.Unavailable)
    }
}
