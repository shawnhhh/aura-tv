package tv.herrin.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Real Activity cold start with public original metadata; the short token disables all network probes. */
@OptIn(ExperimentalTestApi::class)
class P0CachedHomeHeroTest {
    @get:Rule(order=0) val fixture=object:ExternalResource() {
        override fun before() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            val dir=java.io.File(c.filesDir,"tmdb-home-pages");dir.deleteRecursively()
            val parser=HerrinTmdbDiscovery(ArtworkJsonFetcher { url,_->
                val (id,genres)=when {
                    url.contains("with_genres=16&")->31 to "16"
                    url.contains("with_genres=10764")->41 to "10764"
                    url.contains("with_genres=99&")->51 to "99"
                    url.contains("top_rated")->61 to "28"
                    url.contains("/tv/")->21 to "18"
                    else->11 to "28"
                }
                """{"results":[{"id":$id,"title":"Original $id","name":"Original $id","genre_ids":[$genres],"release_date":"2026-01-01","first_air_date":"2026-01-01"}]}"""
            },cacheDir=dir)
            runBlocking { parser.sections.forEach { parser.load(it,"fixture") } }
            Preferences(c).apply { tmdbReadToken="fixture";tvBoxWarehouseUrl="http://127.0.0.1:1/fixture.json" }
        }
        override fun after() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively()
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()

    @Test fun cachedOfflineHomeAndRapidCategoriesKeepTitleAndBackdropOnTheirOwnFirstFilm() {
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("splash-curtain").assertDoesNotExist() }.isSuccess }
        val expected=listOf("首页" to "tmdb-movie-11", "电影" to "tmdb-movie-11",
            "剧集" to "tmdb-tv-21", "动漫" to "tmdb-tv-31", "综艺" to "tmdb-tv-41",
            "纪录片" to "tmdb-movie-51", "Top 250" to "tmdb-movie-61", "电影" to "tmdb-movie-11",
            "首页" to "tmdb-movie-11", "电影" to "tmdb-movie-11", "剧集" to "tmdb-tv-21", "电影" to "tmdb-movie-11")
        for ((category,id) in expected) {
            compose.onNodeWithTag("nav-$category").performClick()
            android.util.Log.i("HerrinCachedHero","asserting_category=$category expected=$id")
            compose.onNodeWithTag("hero-title-$id").assertTextEquals("Original ${id.substringAfterLast('-')}")
            compose.waitUntil(3000) { runCatching { compose.onNodeWithTag("hero-art-$id").assertExists() }.isSuccess }
            if (category == "Top 250") {
                compose.onNodeWithTag("nav-$category").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                compose.onNodeWithTag("nav-$category").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
                compose.onNodeWithTag("card-$id").assertIsFocused()
                compose.onNodeWithTag("card-$id").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
                compose.onNodeWithTag("nav-$category").assertIsFocused()
            }
        }
        compose.onNodeWithTag("source-empty-title").assertDoesNotExist()
        compose.onAllNodesWithText("来自影视源",substring=true).assertCountEquals(0)
    }
}
