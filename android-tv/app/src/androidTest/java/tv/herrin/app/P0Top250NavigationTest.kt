package tv.herrin.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Full original 250-item cache, including two movies which the series projection groups together. */
@OptIn(ExperimentalTestApi::class)
class P0Top250NavigationTest {
    @get:Rule(order=0) val fixture = object : ExternalResource() {
        override fun before() {
            val c = InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            val dir = java.io.File(c.filesDir,"tmdb-home-pages"); dir.deleteRecursively()
            val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { url, _ ->
                val page = Regex("page=(\\d+)").find(url)!!.groupValues[1].toInt()
                val movies = ((page-1)*20+1..minOf(page*20,250)).joinToString(",") { id ->
                    val title = if (id <= 2) "原创排行 第${id}季" else "Original rank $id"
                    """{"id":$id,"title":"$title","release_date":"2026-01-01","genre_ids":[${if(id<=2)16 else 28}]}"""
                }
                """{"results":[$movies]}"""
            }, cacheDir=dir)
            runBlocking { (1..13).forEach { discovery.load(discovery.sections.first { it.key=="top250" },"fixture",it) } }
            Preferences(c).tmdbReadToken = "fixture" // deliberately invalid format: no real TMDB calls
        }
        override fun after() {
            val c = InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively()
        }
    }
    @get:Rule(order=1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun rawRanksRemainUniqueAndFirstMiddleLastPostersNavigateUpAndDownSafely() {
        compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess }
        compose.onNodeWithTag("nav-Top 250").performClick()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("card-tmdb-movie-1").assertExists() }.isSuccess }
        // With the old collapsed group key these two visible entries collide in LazyRow.
        compose.onNodeWithTag("card-tmdb-movie-2").assertExists()
        for (index in listOf(0,124,249)) {
            compose.onNodeWithTag("catalog-film-rail").performScrollToIndex(index)
            val card = "card-tmdb-movie-${index+1}"
            compose.onNodeWithTag(card).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(card).assertIsFocused()
            if (index < 249) {
                compose.onNodeWithTag(card).performKeyInput { pressKey(Key.DirectionRight) }
                compose.onNodeWithTag("card-tmdb-movie-${index+2}").assertIsFocused()
                compose.onNodeWithTag("card-tmdb-movie-${index+2}").performKeyInput { pressKey(Key.DirectionUp) }
            } else compose.onNodeWithTag(card).performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNodeWithTag("nav-Top 250").assertIsFocused()
            compose.onNodeWithTag("nav-Top 250").performKeyInput { pressKey(Key.DirectionDown) }
            val current = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag]
            assertTrue("Down must return to an attached Top250 poster",current.startsWith("card-tmdb-movie-"))
            compose.onNodeWithTag(current).assertIsDisplayed()
        }
        // Same fixture changes between a collapsed anime shelf and the raw chart.
        repeat(4) {
            compose.onNodeWithTag("nav-动漫").performClick()
            compose.onNodeWithTag("nav-Top 250").performClick()
            compose.onNodeWithTag("catalog-film-rail").performScrollToIndex(0)
            compose.onNodeWithTag("card-tmdb-movie-1").assertExists()
            compose.onNodeWithTag("card-tmdb-movie-2").assertExists()
        }
    }
}
