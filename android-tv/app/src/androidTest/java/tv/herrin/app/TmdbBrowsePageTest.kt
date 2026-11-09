package tv.herrin.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Actual TV Activity, cached evidence-filtered metadata, native D-pad and incremental rows. */
class TmdbBrowsePageTest {
    @get:Rule(order=0) val fixture=object:ExternalResource() {
        override fun before() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            clearTmdbBrowseTestCache(c)
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively()
            seedTmdbBrowseTestCache(c,"fixture")
            Preferences(c).tmdbReadToken="fixture" // Invalid live token: this test exercises the real offline cache.
        }
        override fun after() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            clearTmdbBrowseTestCache(c)
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun ready() { compose.waitUntil(15000) { runCatching {
        compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
        compose.onNodeWithTag("hero-title-tmdb-movie-11").assertExists()
    }.isSuccess } }
    @Test fun fivePagesHaveFourIndependentRowsAndSwitchHeroImmediatelyFromCache() {
        ready()
        val start=android.os.SystemClock.elapsedRealtime()
        val expected=mapOf(TmdbBrowsePage.MOVIE to "tmdb-movie-11",TmdbBrowsePage.SERIES to "tmdb-tv-21",
            TmdbBrowsePage.ANIME to "tmdb-tv-31",TmdbBrowsePage.VARIETY to "tmdb-tv-41",
            TmdbBrowsePage.DOCUMENTARY to "tmdb-movie-51")
        for((page,id) in expected) {
            compose.onNodeWithTag("nav-"+page.label).performClick()
            compose.onNodeWithTag("hero-title-$id").assertExists()
            for(spec in TmdbRailSpec.forPage(page)) {
                compose.onNodeWithTag("tmdb-heading-"+spec.key).assertTextEquals(spec.title)
                compose.onNodeWithTag("tmdb-rail-"+spec.key).assertExists()
            }
            compose.onNodeWithTag("catalog-film-rail").assertDoesNotExist()
        }
        val duration=android.os.SystemClock.elapsedRealtime()-start
        android.util.Log.i("HerrinTmdbBrowse","five_cached_pages_ms=$duration physical_tcl=0")
        assertTrue("Cached five-page switching must stay bounded",duration<12000)
    }
    @Test fun nativeDpadTraversesIndependentRowsAndReturnsToTheSameColumn() {
        ready()
        compose.onNodeWithTag("nav-电影").performClick()
        val hot="card-tmdb-movie-11"
        compose.onNodeWithTag(hot).performScrollTo()
        compose.onNodeWithTag(hot).performSemanticsAction(SemanticsActions.RequestFocus){it()}
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5000){runCatching{
            compose.onNodeWithTag("tmdb-movie-domestic-tmdb-movie-100").assertIsFocused().assertIsDisplayed()
        }.isSuccess}
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag(hot).assertIsFocused().assertIsDisplayed()}.isSuccess}
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("tmdb-more-movie-hot").assertIsFocused()
        compose.onNodeWithTag("nav-电影").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        compose.onNodeWithTag("nav-电影").assertIsFocused()
    }
    @Test fun homeKeepsRecentThenTrendingThenDomesticAndNoRegionSubtitle() {
        ready()
        compose.onNodeWithTag("tmdb-heading-recent").assertTextEquals("最近播放")
        compose.onNodeWithTag("tmdb-heading-home-hot").assertTextEquals("热门推荐")
        compose.onNodeWithTag("tmdb-heading-home-domestic").assertTextEquals("国内热门")
        compose.onNodeWithText("大陆 : 香港 : 台湾").assertDoesNotExist()
        compose.onNodeWithTag("card-tmdb-movie-11").performScrollTo().performClick()
        compose.onNodeWithTag("film-detail-list").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("tmdb-heading-home-hot").assertExists()
    }
}
