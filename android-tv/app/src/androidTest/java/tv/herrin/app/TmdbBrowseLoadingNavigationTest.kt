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
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cold production repository with a held HTTP page, rather than a static cache. */
@OptIn(ExperimentalTestApi::class)
class TmdbBrowseLoadingNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coldFirstPagePreviewHasStableUniqueKeysWhileProofAndDpadOverlap() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            if (Regex(".*/(movie|tv)/[0-9]+.*").matches(url)) {
                entered.countDown(); release.await()
            }
            val raw = fixture.load(url, token)
            if ((url.contains("/discover/movie") && url.contains("sort_by=popularity.desc") && !url.contains("with_origin_country"))) {
                val json = org.json.JSONObject(raw)
                val first = json.getJSONArray("results").getJSONObject(0)
                // Model overlapping upstream rows before detail verification.
                json.put("results", org.json.JSONArray().put(first).put(first)
                    .put(org.json.JSONObject(first.toString()).put("id", 72))).toString()
            } else raw
        }), "fixture")
        val requesters = mutableMapOf<String, FocusRequester>()
        val spec = TmdbRailSpec(TmdbBrowsePage.MOVIE, TmdbRailKind.HOT)
        compose.setContent { MaterialTheme {
            LaunchedEffect(state) { state.refresh(TmdbBrowsePage.MOVIE) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TmdbBrowseRails(state, TmdbBrowsePage.MOVIE, emptyList(), true,
                    { requesters.getOrPut(it) { FocusRequester() } }, { _, _ -> }, { _, _ -> })
            }
        } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5000) { state.row(spec).entries.isNotEmpty() }
            compose.waitUntil(5000) { !state.row(spec).loading } // HOT no longer waits for per-title proof.
            assertEquals(2, state.row(spec).entries.size)
            val card = compose.onNodeWithTag("card-tmdb-movie-11")
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            card.performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithTag("card-tmdb-movie-72").assertIsFocused()
            release.countDown()
            compose.waitUntil(5000) { !state.row(spec).loading }
            compose.onNodeWithTag("card-tmdb-movie-72").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitUntil(5000) { runCatching {
                compose.onNodeWithTag("tmdb-movie-domestic-tmdb-movie-101").assertIsFocused()
            }.isSuccess }
        } finally { release.countDown() }
    }

    @Test fun delayedPageAllowsDpadAndIsCancelledOnCategoryChange() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val images = okhttp3.mockwebserver.MockWebServer()
        val imageRequests = java.util.concurrent.atomic.AtomicInteger()
        val original = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().apply {
            original.eraseColor(android.graphics.Color.BLUE)
            assertTrue(original.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this))
        }.toByteArray()
        original.recycle()
        images.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                imageRequests.incrementAndGet()
                return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png")
                    .setBodyDelay(40, TimeUnit.MILLISECONDS).setBody(okio.Buffer().write(bytes))
            }
        }
        images.start()
        val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = AuthorizedPosterCache(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        var decodes: Deferred<List<android.graphics.Bitmap?>>? = null
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            if ((url.contains("/discover/movie") && url.contains("sort_by=popularity.desc") && !url.contains("with_origin_country")) && url.contains("page=2")) {
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
            // Page 2 is intentionally held at the HTTP fixture. It must remain
            // loading while the existing first-page posters accept D-pad input.
            assertTrue("In-flight page must keep its loading state", state.row(spec).loading)
            decodes = imageScope.async {
                (0 until 16).map { i -> async {
                    cache.load(images.url("/original-$i.png").toString(), allowHttp = true, hero = i == 0)
                } }.awaitAll()
            }
            compose.waitUntil(5000) { imageRequests.get() > 0 }
            val card = compose.onNodeWithTag("card-tmdb-movie-11")
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(12) { card.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionUp) } }
            compose.waitUntil(5000) { runCatching { card.assertIsFocused() }.isSuccess }
            compose.runOnIdle { page = TmdbBrowsePage.SERIES }
            compose.onNodeWithTag("tmdb-page-series").assertExists()
            compose.waitUntil(5000) { interrupted.get() && !state.row(spec).loading }
            assertEquals("cancelled page cannot publish into the old category", 1, state.row(spec).page)
            val bitmaps = runBlocking { withTimeout(10000) { requireNotNull(decodes).await() } }
            assertTrue("all original hero/poster requests must really decode", bitmaps.all { it != null })
            assertTrue(bitmaps.first()!!.width >= 960)
            assertTrue(bitmaps.drop(1).all { it!!.width <= 640 })
            assertEquals(16, imageRequests.get())
            val bound = (Runtime.getRuntime().maxMemory() / 12).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024)
            assertTrue("decoded cache must remain bounded", AuthorizedPosterCache.memoryBytes <= bound)
        } finally {
            release.countDown()
            imageScope.cancel()
            images.shutdown()
        }
    }

    @Test fun nextPageReturnAndReplacementKeepNavigationOnCurrentPosters() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(ArtworkJsonFetcher { url, token ->
            if ((url.contains("/discover/movie") && url.contains("sort_by=popularity.desc") && !url.contains("with_origin_country")) && url.contains("page=2")) {
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
    @Test fun emptyThenLoadedHeroDownSurvivesRapidNavigationAndReplacement() {
        val fixture = TmdbBrowseFixture()
        val state = TmdbBrowseState(TmdbBrowseRepository(fixture), "fixture")
        val spec = TmdbRailSpec(TmdbBrowsePage.MOVIE, TmdbRailKind.HOT)
        val requesters = mutableMapOf<String, FocusRequester>()
        val hero = FocusRequester()
        var focused by mutableStateOf("hero")
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val first = TmdbBrowseFocus.first(state, TmdbBrowsePage.MOVIE, emptyList())
                    ?.takeIf(state::isPosterAttached)
                TvButton("播放", Modifier.testTag("hero-play")
                    .then(if (first != null)
                        Modifier.focusProperties { down = requesters.getOrPut(first) { FocusRequester() } }
                    else Modifier)
                    .focusRequester(hero), reduced = true, onFocus = { focused = "hero" }) {}
                TmdbBrowseRails(state, TmdbBrowsePage.MOVIE, emptyList(), true,
                    { requesters.getOrPut(it) { FocusRequester() } },
                    { _, tag -> focused = tag }, { _, _ -> })
            }
        } }
        val button = compose.onNodeWithTag("hero-play")
        button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(6) { button.performKeyInput { pressKey(Key.DirectionDown) } }
        // Empty / failed / pending shelves are not errors in remote navigation.
        compose.runOnIdle { assertTrue(state.row(spec).entries.isEmpty()) }
        runBlocking { state.refresh(TmdbBrowsePage.MOVIE) }
        compose.waitUntil(5000) { state.row(spec).entries.isNotEmpty() &&
            state.isPosterAttached("card-tmdb-movie-11") }
        button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        button.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("card-tmdb-movie-11").assertIsFocused() }.isSuccess
        }
        // A new first-page response may replace the focused poster mid-movement.
        compose.runOnIdle {
            focused = "hero"
            state.rows[spec.key] = state.row(spec).copy(entries = emptyList())
        }
        repeat(12) { compose.onNodeWithTag("hero-play").performKeyInput {
            pressKey(Key.DirectionDown); pressKey(Key.DirectionUp)
        } }
        runBlocking { state.refresh(TmdbBrowsePage.MOVIE, force = true) }
        compose.waitUntil(5000) { state.row(spec).entries.isNotEmpty() }
        compose.onNodeWithTag("hero-play").assertExists()
        assertTrue(focused.isNotEmpty())
    }
}
