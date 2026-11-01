package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/** Playback-only FongMi parser coverage: no film/title/artwork/TMDB state. */
class FongMiPlaybackBackendsTest {
    @Test fun warehouseKeepsAllFiveParserTypesWithoutChangingCmsSite() {
        val cfg = TvBoxConfigParser.parse("""{
          "sites":[{"key":"cms","name":"CMS","api":"https://site.test/api.php"}],
          "parses":[
            {"name":"Browser","type":0,"url":"https://jx.test/?url=","ext":{"flag":["网页"]}},
            {"name":"JSON","type":1,"url":"https://json.test/?url="},
            {"name":"Ext","type":2,"url":"ParseExt"},
            {"name":"Mix","type":3,"url":"ParseMix"},
            {"name":"All","type":4}
          ]}""", "https://site.test/root") as TvBoxConfig.Single
        assertEquals(listOf(0,1,2,3,4), cfg.playbackParsers.map { it.type })
        assertEquals(1, cfg.jsonParsers.size)
        assertEquals("CMS", cfg.sites.single().name)
        assertEquals("https://site.test/api.php", cfg.sites.single().apiUrl)
    }

    @Test fun namedParserDispatchesEachEngineWithoutOpeningAPlayer() {
        val parsers = listOf(
            TvBoxPlaybackParser("Browser",0,"https://web.test/?url="),
            TvBoxPlaybackParser("JSON",1,"https://json.test/?url="),
            TvBoxPlaybackParser("Ext",2,"JsonExt"),
            TvBoxPlaybackParser("Mix",3,"MixExt"),
            TvBoxPlaybackParser("All",4,""))
        fun route(name: String): FongMiPlayRoute =
            FongMiPlaybackRouter.route("""{"parse":1,
                "playUrl":"parse:$name","url":"https://watch.test/film.html"}""",
                "网页", emptyList(),false,playbackParsers=parsers)
        assertTrue(route("Browser") is FongMiPlayRoute.BrowserParser)
        assertTrue(route("JSON") is FongMiPlayRoute.JsonParser)
        assertTrue(route("Ext") is FongMiPlayRoute.JarParser)
        assertTrue(route("Mix") is FongMiPlayRoute.JarParser)
        assertTrue(route("All") is FongMiPlayRoute.Aggregate)
    }

    @Test fun browserCandidatesRequireExplicitPermissionAndRealMediaExtension() {
        assertEquals("https://media.test/film.m3u8",
            FongMiBrowserPlayback.candidate("https://media.test/film.m3u8?sig=xyz",false)
                ?.substringBefore("?"))
        assertNull(FongMiBrowserPlayback.candidate("https://media.test/film.html",true))
        assertNull(FongMiBrowserPlayback.candidate("file:///sdcard/secrets.m3u8",true))
        assertNull(FongMiBrowserPlayback.candidate("http://media.test/film.m3u8",false))
        assertNotNull(FongMiBrowserPlayback.candidate("http://media.test/film.m3u8",true))
    }

    @Test fun browserParserSelectionDoesNotCaptureSignedDirectMedia() {
        val parsers = listOf(TvBoxPlaybackParser("Browser",0,
            "https://jx.test/?url=", listOf("网页")))
        assertNull(FongMiBrowserPlayback.choose(
            "https://media.test/film.m3u8", "网页", parsers, false))
        assertEquals(parsers.first(), FongMiBrowserPlayback.choose(
            "https://watch.test/film.html", "网页", parsers, false))
        assertNull(FongMiBrowserPlayback.choose(
            "https://watch.test/film.html", "其它", parsers, false))
    }

    @Test fun aggregateReturnsConfiguredAlternativesAndNoUnsupportedPluginFallback() {
        val parsers = listOf(
            TvBoxPlaybackParser("All",4,""),
            TvBoxPlaybackParser("JSON",1,"https://api.test/?url="),
            TvBoxPlaybackParser("Browser",0,"https://web.test/?url="),
            TvBoxPlaybackParser("Private",2,"JsonPrivate"))
        val route = FongMiPlaybackRouter.route("""{
            "parse":1,"url":"https://watch.test/watch.html"
        }""","未知",emptyList(),false,playbackParsers=parsers)
        assertTrue(route is FongMiPlayRoute.Aggregate)
        val candidates = (route as FongMiPlayRoute.Aggregate).parsers
        assertEquals(listOf(1,0), candidates.map { it.type })
    }
}
