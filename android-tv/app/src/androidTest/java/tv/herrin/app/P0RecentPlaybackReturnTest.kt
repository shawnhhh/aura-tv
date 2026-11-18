package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Public recent metadata outside the home catalog -> freshly authorized NAS lookup -> real player -> same card. */
class P0RecentPlaybackReturnTest {
    private val server=MockWebServer()
    private val discoveryFails=java.util.concurrent.atomic.AtomicBoolean(false)
    private val discoveries=java.util.concurrent.atomic.AtomicInteger()
    @get:Rule(order=0) val fixture=object:ExternalResource() {
        override fun before() {
            val inst=InstrumentationRegistry.getInstrumentation()
            val c=inst.targetContext
            HerrinPlaybackDiagnosticJournal.clear()
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            c.getSharedPreferences("herrin-watch-history",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively(); clearTmdbBrowseTestCache(c)
            val video=inst.context.assets.open("synthetic.mp4").use { it.readBytes() }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.method=="PROPFIND" && discoveryFails.get() -> { discoveries.incrementAndGet(); MockResponse().setResponseCode(503) }
                    request.method=="PROPFIND" -> { discoveries.incrementAndGet(); MockResponse().setResponseCode(207)
                        .setHeader("Content-Type","application/xml").setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/Interstellar.2014.1080p.mp4</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>""") }
                    request.method=="GET" && request.getHeader("Range")=="bytes=0-0" -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Type","video/mp4").setHeader("Content-Range","bytes 0-0/${video.size}")
                        .setHeader("Accept-Ranges","bytes").setBody(Buffer().write(video,0,1))
                    request.method=="GET" -> MockResponse().setHeader("Content-Type","video/mp4")
                        .setHeader("Accept-Ranges","bytes").setBody(Buffer().write(video))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            Preferences(c).apply { tmdbReadToken="fixture";nasWebDavUrl=server.url("/dav/").toString();nasAllowHttp=true }
            val f=Film("tmdb-movie-157336","Interstellar","","电影","2014","Original video fixture",1,
                metadataSource="TMDB",playbackEpisodes=listOf(PlaybackEpisode(1,"正片",listOf(PlaybackLine("retired","retired","https://retired.invalid/not-persisted.mp4")))))
            PlaybackHistory(c).apply {
                val ticket=begin("tmdb:discovery",f.id,1,"retired")
                updateSession(ticket,"tmdb:discovery",f.id,1,"retired",3000,30000,false)
                recordFilm(ticket,"tmdb:discovery",f,1,"retired",3000,30000)
            }
        }
        override fun after() {
            server.shutdown()
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            c.getSharedPreferences("herrin-watch-history",0).edit().clear().commit()
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()

    @Test fun recentOutsideTmdbHomeDirectlyPlaysFreshAuthorizedSourceAndReturnsToCard() {
        val card="card-tmdb-movie-157336"
        compose.waitUntil(15000) { runCatching {
            compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
            compose.onNodeWithTag(card).assertExists()
        }.isSuccess }
        compose.onNodeWithTag(card).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(card).performClick()
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("player-root-focus").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("film-detail-list").assertDoesNotExist()
        compose.waitUntil(30000) { HerrinPlaybackDiagnosticJournal.export().contains("CLICK_TO_FIRST_FRAME") }
        assertTrue(HerrinPlaybackDiagnosticJournal.export().contains("CLICK_TO_MEDIA_READY"))
        assertTrue("Current NAS must be searched/probed rather than replaying the retired URL",server.requestCount>=2)
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(card).assertIsDisplayed().assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
    }
    private fun homeReady() {
        compose.waitUntil(15000) { runCatching {
            compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
            compose.onNodeWithTag("hero-title-tmdb-movie-157336").assertExists()
        }.isSuccess }
    }

    private fun playAndReturnTo(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("player-root-focus").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("film-detail-list").assertDoesNotExist()
        compose.waitUntil(30000) { HerrinPlaybackDiagnosticJournal.export().contains("CLICK_TO_FIRST_FRAME") }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused() }.isSuccess }
    }

    @Test fun heroDetailsRemainsDetailsAndHeroPlayDirectlyDecodesThenRestoresButtonFocus() {
        homeReady()
        compose.onNodeWithTag("details").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("details").performClick()
        compose.onNodeWithTag("film-detail-list").assertExists()
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("details").assertIsFocused() }.isSuccess }
        playAndReturnTo("play")
    }

    @Test fun myContinueWatchingDirectlyDecodesAndReturnsToTheSameCard() {
        homeReady()
        compose.onNodeWithTag("nav-我的").performClick()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("card-tmdb-movie-157336").assertExists() }.isSuccess }
        playAndReturnTo("card-tmdb-movie-157336")
        compose.onNodeWithTag("recent-heading").assertTextContains("继续观看", substring = true)
    }

    @Test fun failedQuickDiscoveryFallsBackToDetailAndReusesCompletedNodeState() {
        discoveryFails.set(true)
        homeReady()
        compose.onNodeWithTag("play").performClick()
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("film-detail-list").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("one-tap-loading").assertDoesNotExist()
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        val completed = discoveries.get()
        assertTrue("The explicitly requested source must actually be searched", completed > 0)
        compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("source-region"))
        compose.waitForIdle()
        org.junit.Assert.assertEquals("Returning to detail must reuse the terminal discovery outcome", completed, discoveries.get())
        compose.onNodeWithTag("detail-play-cta").assertDoesNotExist()
    }

}
