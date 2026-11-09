package tv.herrin.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Actual TV posters and production repository: a duplicate next page must finish exactly once. */
class TmdbBrowsePaginationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun endPosterLoadsOneDuplicatePageWithoutSelfCancellationOrRequestCascade() {
        val fixture=TmdbBrowseFixture().apply { dense=true }
        val nextRequests=AtomicInteger()
        val repository=TmdbBrowseRepository(ArtworkJsonFetcher{url,key->
            val uri=URI(url)
            if(uri.path.endsWith("/trending/movie/week")) {
                val page=Regex("(?:[?&])page=([0-9]+)").find(url)!!.groupValues[1].toInt()
                if(page>1)nextRequests.incrementAndGet()
                JSONObject(fixture.load(url.replace("page=$page","page=1"),key))
                    .put("page",page).put("total_pages",50).toString()
            } else fixture.load(url,key)
        })
        val state=TmdbBrowseState(repository,"fixture")
        runBlocking { state.refresh(TmdbBrowsePage.MOVIE) }
        val spec=TmdbRailSpec(TmdbBrowsePage.MOVIE,TmdbRailKind.HOT)
        val initial=state.row(spec).entries.map{it.film.id}
        assertEquals(20,initial.size)
        val focus=mutableMapOf<String,FocusRequester>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TmdbBrowseRails(state,TmdbBrowsePage.MOVIE,emptyList(),true,
                    {focus.getOrPut(it){FocusRequester()}}, {_,_->}, {_,_->})
            }
        } }
        compose.onNodeWithTag("tmdb-rail-movie-hot").performScrollToIndex(18)
        val tag="card-"+initial[18]
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus){it()}
        compose.waitUntil(5000){state.row(spec).page==2 && !state.row(spec).loading}
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("One focus arrival must not exhaust all duplicate API pages",1,nextRequests.get())
        assertEquals(initial,state.row(spec).entries.map{it.film.id})
        assertTrue(state.row(spec).hasMore)
        assertFalse(state.row(spec).error)
        compose.onNodeWithTag(tag).assertIsFocused()
    }
}
