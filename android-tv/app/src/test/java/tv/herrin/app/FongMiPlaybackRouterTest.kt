package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Upstream FongMi/TV ParseJob routing compatibility without any UI or TMDB.
 * The route decision must not execute JavaScript or inspect third-party pages.
 */
class FongMiPlaybackRouterTest {
    private val parsers = listOf(
        TvBoxJsonParse("Official JSON", "https://parser.test/json?url=", listOf("官方"))
    )

    @Test fun explicitJsonDirectiveUsesProviderEndpointWithoutRequiringGlobalParseFlag() {
        val route = FongMiPlaybackRouter.route("""{
            "parse":0,"playUrl":"json:https://provider.test/parse?url=",
            "url":"https://watch.test/film/42.html"
        }""", "官方", emptyList(), false)
        assertTrue(route is FongMiPlayRoute.JsonParser)
        val selected = route as FongMiPlayRoute.JsonParser
        assertEquals("https://provider.test/parse?url=", selected.parser.prefix)
        assertEquals("https://watch.test/film/42.html", selected.originalUrl)
    }

    @Test fun namedParserDoesNotDependOnProviderParseFlag() {
        val route = FongMiPlaybackRouter.route("""{
            "parse":0,"playUrl":"parse:Official JSON",
            "url":"https://watch.test/watch?id=7"
        }""", "其它", parsers, false)
        assertEquals(parsers.first(), (route as FongMiPlayRoute.JsonParser).parser)
        assertTrue(FongMiPlaybackRouter.route("""{
            "playUrl":"parse:Missing","url":"https://watch.test/watch?id=7"
        }""", "官方", parsers, false) is FongMiPlayRoute.Unavailable)
    }

    @Test fun signedExtensionlessDirectMediaIsNotCapturedByFlagParser() {
        val route = FongMiPlaybackRouter.route("""{
            "parse":0,"url":"https://video.test/secure/stream?id=1&sign=abc"
        }""", "官方", parsers, false)
        assertEquals("https://video.test/secure/stream?id=1&sign=abc",
            (route as FongMiPlayRoute.Direct).playback.media.uri)
    }

    @Test fun signedHlsWithDeclaredMimeStaysDirectEvenWhenParseFlagIsOne() {
        val route = FongMiPlaybackRouter.route("""{
            "parse":1,"format":"application/vnd.apple.mpegurl",
            "url":"https://video.test/secure/play?id=42"
        }""", "官方", parsers, false)
        assertEquals(PlaybackFormatRetry.HLS,
            (route as FongMiPlayRoute.Direct).playback.media.declaredMime)
    }

    @Test fun htmlOrParseFlagRoutesOnlyIntoTrustedConfiguredJsonParser() {
        val route = FongMiPlaybackRouter.route("""{
            "parse":1,"url":"https://watch.test/play.html?id=7"
        }""", "官方", parsers, false)
        assertTrue(route is FongMiPlayRoute.JsonParser)
        assertTrue(FongMiPlaybackRouter.route("""{
            "parse":1,"url":"https://watch.test/play.html?id=7"
        }""", "官方", emptyList(), false) is FongMiPlayRoute.Unavailable)
        assertTrue(FongMiPlaybackRouter.route("""{
            "parse":0,"url":"https://watch.test/play.html?id=7"
        }""", "官方", emptyList(), false) is FongMiPlayRoute.Unavailable)
    }

    @Test fun parserUsesSelectedQualityObjectAndRejectsUnsafeUrls() {
        val chosen = FongMiPlaybackRouter.route("""{
            "parse":1,
            "url":{"position":1,"values":[
                {"v":"https://watch.test/low.html"},
                {"v":"https://watch.test/high.html"}]}
        }""", "官方", parsers, false) as FongMiPlayRoute.JsonParser
        assertEquals("https://watch.test/high.html", chosen.originalUrl)
        assertTrue(FongMiPlaybackRouter.route("""{
            "parse":1,"playUrl":"json:javascript:alert(1)",
            "url":"https://watch.test/film.html"
        }""", "官方", parsers, false) is FongMiPlayRoute.Unavailable)
        assertTrue(FongMiPlaybackRouter.route("""{
            "parse":1,"url":"file:///sdcard/private.mp4"
        }""", "官方", parsers, false) is FongMiPlayRoute.Unavailable)
    }

    @Test fun parserUrlMatchesRawFongMiConcatenationAndHonorsHttpConsent() {
        val parser = parsers.first()
        val raw = "https://watch.test/view?id=42&session=a/b"
        assertEquals("https://parser.test/json?url=" + raw,
            TvBoxJsonPlayback.parserUrl(parser, raw, false))
        assertNull(TvBoxJsonPlayback.parserUrl(parser, "http://watch.test/view", false))
        assertNotNull(TvBoxJsonPlayback.parserUrl(parser, "http://watch.test/view", true))
    }

    @Test fun parserResponseCarriesSameOriginCookieButNeverLeaksCrossOriginSecrets() {
        val parser = TvBoxJsonParse("Private JSON", "https://parser.test/parse?url=")
        val same = TvBoxJsonPlayback.decode("""{"url":"https://parser.test/video.mp4",
            "header":{"Cookie":"sid=private","Authorization":"Bearer private"}}""",
            parser, false) as PlaybackResult.Ready
        assertEquals("sid=private", same.media.headers["Cookie"])
        val other = TvBoxJsonPlayback.decode("""{"url":"https://cdn.test/video.mp4",
            "header":{"Cookie":"sid=private","Authorization":"Bearer private"},
            "ua":"FongMiClient","Referer":"https://watch.test/"}""",
            parser, false) as PlaybackResult.Ready
        assertEquals("FongMiClient", other.media.headers["User-Agent"])
        assertEquals("https://watch.test/", other.media.headers["Referer"])
        assertFalse(other.media.headers.containsKey("Cookie"))
        assertFalse(other.media.headers.containsKey("Authorization"))
    }
}
