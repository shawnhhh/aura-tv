package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackHistoryTest {
    @Test fun playbackOffsetsPersistAndAreIsolatedByCatalogAndLine() {
        val scope = "test-catalog-${System.nanoTime()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = PlaybackHistory(context)
        val item = Film("history-fixture", "演示", "", "电影", "2026", "", 1,
            playbackEpisodes = listOf(PlaybackEpisode(1, "第一集",
                listOf(PlaybackLine("line-a", "线路 A", "https://example.org/demo.mp4")))))
        assertNull(history.load(scope, item.id, 1, "line-a"))
        history.update(scope, item.id, 1, "line-a", 30_000, 120_000)
        val saved = history.load(scope, item.id, 1, "line-a")!!
        assertEquals(30_000L, saved.positionMs)
        assertEquals(0.25f, saved.fraction, 0.001f)
        assertNull(history.load(scope, item.id, 1, "line-b"))
        assertNull(history.load(scope + "-different", item.id, 1, "line-a"))
        assertEquals(item.id, history.recent(scope, listOf(item)).single().id)
        assertTrue(history.recent(scope + "-different", listOf(item)).isEmpty())

        // The object is recreated, proving persisted storage rather than a Compose-only cache.
        assertEquals(30_000L, PlaybackHistory(context).load(scope, item.id, 1, "line-a")!!.positionMs)
        history.update(scope, item.id, 1, "line-a", 119_000, 120_000, ended = true)
        assertNull(history.load(scope, item.id, 1, "line-a"))
        assertTrue(history.recent(scope, listOf(item)).isEmpty())
    }

    @Test fun rejectsInvalidPositionsAndSupportsExplicitRestart() {
        val scope = "resume-negative-${System.nanoTime()}"
        val history = PlaybackHistory(InstrumentationRegistry.getInstrumentation().targetContext)
        history.update(scope, "film", 1, "source", 500, 20_000)
        assertNull(history.load(scope, "film", 1, "source"))
        history.update(scope, "film", 1, "source", 5_000, 20_000)
        assertNotNull(history.load(scope, "film", 1, "source"))
        history.clear(scope, "film", 1, "source")
        assertNull(history.load(scope, "film", 1, "source"))
    }
}
