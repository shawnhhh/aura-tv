package tv.herrin.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity receives changed public metadata while a native poster retains D-pad focus. */
class TmdbBrowseRefreshTest {
    private val token="SyntheticBrowseRefreshReadAccess"
    private val release=CountDownLatch(1)
    @get:Rule(order=0) val fixture=object:ExternalResource() {
        override fun before() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            clearTmdbBrowseTestCache(c)
            File(c.filesDir,"tmdb-home-pages").deleteRecursively()
            seedTmdbBrowseTestCache(c,token)
            // A valid stale cache paints immediately; the selected page then refreshes in the background.
            File(c.filesDir,"tmdb-browse-v2").listFiles().orEmpty().filter{"-page-" in it.name}.forEach {
                it.writeText(JSONObject(it.readText()).put("saved",0).toString())
            }
            val changed=TmdbBrowseFixture().apply { variant=1 }
            MetadataFetchers.overrideForTests=ArtworkJsonFetcher { url,key ->
                when {
                    url.contains("/configuration") -> "{\"images\":{}}"
                    url.contains("/search/") -> "{\"results\":[]}"
                    else -> {
                        check(release.await(15,TimeUnit.SECONDS)){"Refresh fixture was not released"}
                        changed.load(url,key)
                    }
                }
            }
            Preferences(c).tmdbReadToken=token
        }
        override fun after() {
            release.countDown()
            MetadataFetchers.overrideForTests=null
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            clearTmdbBrowseTestCache(c)
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun changedNetworkOrderPublishesOnlyAfterTheFocusedRailIsLeft() {
        compose.waitUntil(15000){runCatching{
            compose.onNodeWithTag("splash-curtain").assertDoesNotExist()
            compose.onNodeWithTag("hero-title-tmdb-movie-11").assertExists()
        }.isSuccess}
        compose.onNodeWithTag("card-tmdb-movie-11").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus){it()}
        release.countDown()
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntil(10000){cachedTmdbBrowseTestFilms(c,token).any{it.id=="tmdb-movie-12"}}
        compose.onNodeWithTag("card-tmdb-movie-11").assertIsFocused()
        compose.onNodeWithTag("card-tmdb-movie-12").assertDoesNotExist()
        compose.onNodeWithTag("nav-首页").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        compose.waitUntil(5000){runCatching{
            compose.onNodeWithTag("card-tmdb-movie-12").assertExists()
        }.isSuccess}
        compose.onNodeWithTag("nav-首页").assertIsFocused()
        assertFalse(compose.activity.isFinishing)
    }
}
