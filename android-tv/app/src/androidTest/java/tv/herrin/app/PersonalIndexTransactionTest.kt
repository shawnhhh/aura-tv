package tv.herrin.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No network/player fixture: prove scanner notifications cannot retire a live selection. */
class PersonalIndexTransactionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun indexChangesKeepTheOriginalRouteThroughDetailIntentAndPlayerThenRefreshOnce() {
        val screen = mutableStateOf("detail")
        val revision = mutableStateOf(0L)
        val state = TmdbScreenState()
        val film = "tmdb-movie-123"
        state.tmdbPlaybackRoutes.personal(film)
        state.requestedDiscoveryHits[film] = "original-hit"
        state.loadedDiscoveryHits[film] = "original-hit"
        compose.setContent { PersonalLibraryIndexChanges(revision.value, screen.value, state) }
        compose.waitForIdle()
        for ((index, phase) in listOf("detail", "play-intent", "player").withIndex()) {
            compose.runOnIdle { screen.value = phase; revision.value = (index + 1).toLong() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotNull("Scan must preserve the selected route in $phase", state.tmdbPlaybackRoutes.forFilm(film))
                assertEquals("original-hit", state.loadedDiscoveryHits[film])
                assertTrue(state.sourceSelectionReady(film))
                assertEquals("No lookup restart during the transaction", 0, state.tmdbLookupEpoch)
            }
        }
        compose.runOnIdle { screen.value = "browse" }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(state.tmdbPlaybackRoutes.forFilm(film))
            assertEquals("Coalesce all deferred index notifications", 1, state.tmdbLookupEpoch)
            screen.value = "settings"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, state.tmdbLookupEpoch) }
    }

    @Test fun queuedBrowseInvalidationReadsTheNewSceneBeforeRetiringRoutes() {
        val live = java.util.concurrent.atomic.AtomicReference("browse")
        val revision = mutableStateOf(0L)
        val state = TmdbScreenState()
        state.tmdbPlaybackRoutes.personal("private-file")
        // The scheduled effect belongs to an older browse composition.
        compose.setContent { PersonalLibraryIndexChanges(revision.value, "browse", state, currentScreen = live::get) }
        compose.runOnIdle { live.set("play-intent"); revision.value = 1L }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(state.tmdbPlaybackRoutes.forFilm("private-file"))
            assertEquals(0, state.tmdbLookupEpoch)
            live.set("browse"); revision.value = 2L
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(state.tmdbPlaybackRoutes.forFilm("private-file"))
            assertEquals(1, state.tmdbLookupEpoch)
        }
    }
}
