package tv.herrin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cold production repository with a held HTTP page, rather than a static cache. */
class TmdbBrowseLoadingNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedPageAllowsDpadAndIsCancelledOnCategoryChange() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            if (url.contains("/trending/movie/week") && url.contains("page=2")) {
                entered.countDown()
                try { check(release.await(15, TimeUnit.SECONDS)) }
                catch (cancel: InterruptedException) { interrupted.set(true); throw cancel }
            }
            fixture.load(url, token)
        }), "fixture")
        runBlocking { state.refresh(TmdbBrowsePage.MOVIE); state.refresh(TmdbBrowsePage.SERIES) }
        var page by mutableStateOf(TmdbBrowsePage.MOVIE)
        val requesters = mutableMapOf<String, FocusRequester>()
        val spec = TmdbRailSpec(TmdbBrowsePage.MOVIE, TmdbRailKind.HOT)
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TmdbBrowseRails(state, page, emptyList(), true,
                    { requesters.getOrPut(it) { FocusRequester() } }, { _, _ -> }, { _, _ -> })
            }
        } }
        try {
            compose.onNodeWithTag("tmdb-more-movie-hot").performScrollTo().performClick()
            assertTrue("next HTTP page must actually start", entered.await(5, TimeUnit.SECONDS))
            assertTrue(state.row(spec).loading)
            val card = compose.onNodeWithTag("card-tmdb-movie-11")
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(12) { card.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionUp) } }
            compose.waitUntil(5000) { runCatching { card.assertIsFocused() }.isSuccess }
            compose.runOnIdle { page = TmdbBrowsePage.SERIES }
            compose.onNodeWithTag("tmdb-page-series").assertExists()
            compose.waitUntil(5000) { interrupted.get() && !state.row(spec).loading }
            assertEquals("cancelled page cannot publish into the old category", 1, state.row(spec).page)
        } finally { release.countDown() }
    }

    @Test fun nextPageReturnAndReplacementKeepNavigationOnCurrentPosters() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            if (url.contains("/trending/movie/week") && url.contains("page=2")) {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
            }
            fixture.load(url, token)
        }), "fixture")
        runBlocking { state.refresh(TmdbBrowsePage.MOVIE) }
        val requesters = mutableMapOf<String, FocusRequester>()
        val spec = TmdbRailSpec(TmdbBrowsePage.MOVIE, TmdbRailKind.HOT)
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TmdbBrowseRails(state, TmdbBrowsePage.MOVIE, emptyList(), true,
                    { requesters.getOrPut(it) { FocusRequester() } }, { _, _ -> }, { _, _ -> })
            }
        } }
        try {
            compose.onNodeWithTag("tmdb-more-movie-hot").performScrollTo().performClick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val card = compose.onNodeWithTag("card-tmdb-movie-11")
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            release.countDown()
            card.performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitUntil(5000) { state.row(spec).page == 2 && !state.row(spec).loading }
            val domestic = compose.onNodeWithTag("tmdb-movie-domestic-tmdb-movie-100")
            compose.waitUntil(5000) { runCatching { domestic.assertIsFocused() }.isSuccess }
            // Replace the old first page while focus belongs to another rail.
            fixture.variant = 1
            runBlocking { state.refresh(TmdbBrowsePage.MOVIE, true) }
            domestic.performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitUntil(5000) { runCatching {
                compose.onNodeWithTag("card-tmdb-movie-12").assertIsFocused()
            }.isSuccess }
            assertFalse(state.row(spec).error)
        } finally { release.countDown() }
    }
}
