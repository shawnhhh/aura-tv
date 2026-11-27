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
import org.junit.rules.TestWatcher
import org.junit.rules.TestName
import org.junit.runner.Description

/** Public recent metadata outside the home catalog -> freshly authorized NAS lookup -> real player -> same card. */
class P0RecentPlaybackReturnTest {
    private val server=MockWebServer()
    private val discoveryFails=java.util.concurrent.atomic.AtomicBoolean(false)
    private val observedRequests=java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val discoveries=java.util.concurrent.atomic.AtomicInteger()
    @get:Rule(order=0) val testName = TestName()
    @get:Rule(order=1) val fixture=object:ExternalResource() {
        override fun before() {
            discoveryFails.set(testName.methodName == "failedInitialImportAndQuickLookupFallsBackWithoutAnImplicitRescan")
            val inst=InstrumentationRegistry.getInstrumentation()
            val c=inst.targetContext
            HerrinPlaybackDiagnosticJournal.clear()
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            c.getSharedPreferences("herrin-watch-history",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively(); clearTmdbBrowseTestCache(c)
            val video=inst.context.assets.open("playback-diagnostics/baseline-72.mp4").use { it.readBytes() }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    // Original loopback fixture only; never record headers, secrets or real providers.
                    observedRequests.add("${request.method} ${request.path} RANGE=${request.getHeader("Range")}")
                    return when {
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
            }
            server.start()
            Preferences(c).apply { tmdbReadToken="fixture";nasWebDavUrl=server.url("/dav/").toString();nasAllowHttp=true }
            val f=Film("tmdb-movie-157336","Interstellar","","电影","2014","Original video fixture",1,
                metadataSource="TMDB",playbackEpisodes=listOf(PlaybackEpisode(1,"正片",listOf(PlaybackLine("retired","retired","https://retired.invalid/not-persisted.mp4")))))
            PlaybackHistory(c).apply {
                val ticket=begin("tmdb:discovery",f.id,1,"retired")
                updateSession(ticket,"tmdb:discovery",f.id,1,"retired",30000,72000,false)
                recordFilm(ticket,"tmdb:discovery",f,1,"retired",30000,72000)
            }
        }
        override fun after() {
            server.shutdown()
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            c.getSharedPreferences("herrin-watch-history",0).edit().clear().commit()
        }
    }
    @get:Rule(order=2) val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=3) val failureEvidence=object:TestWatcher() {
        override fun failed(error:Throwable?,description:Description) {
            // The watcher supplements the original failure; it never replaces it.
            val state=runCatching { compose.onRoot(useUnmergedTree=true).printToString(maxDepth=12) }
                .getOrElse { "SEMANTICS_UNAVAILABLE" }
            val message="RECENT_PLAYBACK_FAILURE case=${description.methodName}\n" +
                "REQUESTS=${observedRequests.joinToString(" | ")}\n" +
                HerrinPlaybackDiagnosticJournal.export() + "\n" + state
            message.chunked(2500).forEachIndexed { index,chunk ->
                android.util.Log.e("HerrinRecentFailure", "CHUNK=$index $chunk")
            }
        }
    }

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
        assertResumedPosition()
        assertTrue(HerrinPlaybackDiagnosticJournal.export().contains("CLICK_TO_MEDIA_READY"))
        assertTrue("Current NAS must be searched/probed rather than replaying the retired URL",server.requestCount>=2)
        val inst=InstrumentationRegistry.getInstrumentation()
        exitPlayer()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("film-detail-list").assertExists() }.isSuccess }
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(card).assertIsDisplayed().assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
    }
    private fun exitPlayer() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        android.os.SystemClock.sleep(40)
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun assertResumedPosition() {
        // A fresh start cannot naturally reach 30s within this 5s observation.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5000) { runCatching {
            compose.onNodeWithTag("player-position-label").assertTextContains("00:3", substring = true)
        }.isSuccess }
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
        assertResumedPosition()
        exitPlayer()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("film-detail-list").assertExists() }.isSuccess }
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused() }.isSuccess }
    }

    @Test fun heroDetailsRemainsDetailsAndDetailPlayDecodesThenRestoresFocus() {
        homeReady()
        compose.onNodeWithTag("details").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("details").performClick()
        compose.onNodeWithTag("film-detail-list").assertExists()
        compose.onNodeWithTag("screen-first").assertDoesNotExist()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("detail-favorite").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("details").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("play").assertDoesNotExist()
        compose.onNodeWithTag("details").performClick()
        compose.waitUntil(10000) { runCatching {
            compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("detail-play-cta"))
            compose.onNodeWithTag("detail-play-cta").assertIsDisplayed()
        }.isSuccess }
        compose.onNodeWithTag("detail-play-cta").performClick()
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("player-root-focus").assertIsDisplayed() }.isSuccess }
        compose.waitUntil(30000) { HerrinPlaybackDiagnosticJournal.export().contains("CLICK_TO_FIRST_FRAME") }
        assertResumedPosition()
        exitPlayer()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("film-detail-list").assertExists() }.isSuccess }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("details").assertIsFocused() }.isSuccess }
    }

    @Test fun myContinueWatchingDirectlyDecodesAndReturnsToTheSameCard() {
        homeReady()
        compose.onNodeWithTag("nav-我的").performClick()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("card-tmdb-movie-157336").assertExists() }.isSuccess }
        playAndReturnTo("card-tmdb-movie-157336")
        compose.onNodeWithTag("recent-heading").assertTextContains("继续观看", substring = true)
    }

    @Test fun failedInitialImportAndQuickLookupFallsBackWithoutAnImplicitRescan() {
        homeReady()
        compose.waitUntil(8000) {
            discoveries.get() > 0 && HerrinPlaybackDiagnosticJournal.export().contains("PERSONAL SCAN_FAILED HTTP=503")
        }
        val completed = discoveries.get()
        assertTrue("The controlled initial import must have reached the unavailable server", completed > 0)
        compose.onNodeWithTag("play").assertDoesNotExist()
        compose.onNodeWithTag("details").performClick()
        compose.waitUntil(30000) { runCatching { compose.onNodeWithTag("film-detail-list").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("one-tap-loading").assertDoesNotExist()
        compose.onNodeWithTag("player-root-focus").assertDoesNotExist()
        compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("source-region"))
        compose.waitForIdle()
        org.junit.Assert.assertEquals("Returning to detail must not implicitly restart the failed storage scan", completed, discoveries.get())
        compose.onNodeWithTag("detail-play-cta").assertDoesNotExist()
    }

}
