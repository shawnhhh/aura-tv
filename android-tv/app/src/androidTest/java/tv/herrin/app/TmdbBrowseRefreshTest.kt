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

/** Real Activity receives changed public metadata while a native poster retains D-pad focus. */
class TmdbBrowseRefreshTest {
    private val token="SyntheticBrowseRefreshReadAccess"
    private val release=CountDownLatch(1)
    private val entered=java.util.concurrent.atomic.AtomicInteger()
    private val returned=java.util.concurrent.atomic.AtomicInteger()
    private val errors=java.util.concurrent.atomic.AtomicInteger()
    private val interrupted=java.util.concurrent.atomic.AtomicInteger()
    private val changedRows=java.util.concurrent.atomic.AtomicInteger()
    private var errorsBeforeRelease=0
    private val firstEnteredAt=java.util.concurrent.atomic.AtomicLong()
    private var waitBeforeReleaseMs=0L
    private val holdBeforeRelease=InstrumentationRegistry.getArguments().getString("tmdbHoldRefresh")=="true"
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
                        entered.incrementAndGet()
                        firstEnteredAt.compareAndSet(0,android.os.SystemClock.elapsedRealtime())
                        try {
                            // This is a controlled response gate, not a server timeout.
                            // Its owner always releases it in after(), including failure;
                            // Activity cancellation can also interrupt the blocked call.
                            release.await()
                            changed.load(url,key).also { raw ->
                                returned.incrementAndGet()
                                val rows=JSONObject(raw).optJSONArray("results")
                                if(rows!=null && (0 until rows.length()).any{rows.optJSONObject(it)?.optInt("id")==12}) changedRows.incrementAndGet()
                            }
                        } catch(cancel:InterruptedException) {
                            interrupted.incrementAndGet(); throw cancel
                        } catch(error:Exception) { errors.incrementAndGet(); throw error }
                    }
                }
            }
            Preferences(c).tmdbReadToken=token
        }
        override fun after() {
            release.countDown()
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            // Synthetic fixture counters only. Preserve every original assertion.
            File(c.filesDir,"tmdb-refresh-diagnostic.json").writeText(JSONObject()
                .put("entered",entered.get()).put("returned",returned.get()).put("errors",errors.get())
                .put("cancelled_requests",interrupted.get())
                .put("held_before_release",holdBeforeRelease).put("errors_before_release",errorsBeforeRelease)
                .put("fixture_wait_before_release_ms",waitBeforeReleaseMs)
                .put("responses_with_changed_id",changedRows.get())
                .put("cached_changed_id",cachedTmdbBrowseTestFilms(c,token).any{it.id=="tmdb-movie-12"}).toString())
            MetadataFetchers.overrideForTests=null
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
        if(holdBeforeRelease) Thread.sleep(16000) // Opt-in reproduction of a slow TV establishing focus.
        errorsBeforeRelease=errors.get()
        waitBeforeReleaseMs=firstEnteredAt.get().takeIf{it>0}?.let{android.os.SystemClock.elapsedRealtime()-it} ?: 0
        release.countDown()
        assertEquals("Fixture must not fail before the test explicitly releases it",0,errorsBeforeRelease)
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
