package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M464TvBoxCatalogSnapshotTest {
    @Test fun restoresPublicFilmDisplayButNeverPersistsSignedPlaybackUrls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = "http://unit-test.invalid/warehouse-" + System.nanoTime()
        val site = "test-site"
        val secret = "PRIVATE_MEDIA_TOKEN"
        val original = Film("spider-abc-123-0", "飞行家", "", "电影", "2004",
            "来自公开电影资料的剧情简介", R.drawable.photofilm_orbit,
            artworkUrl = "https://example.org/poster.jpg?token=$secret",
            backdropUrl = "https://image.tmdb.org/t/p/original/backdrop.jpg",
            spiderVodId = "private-id-$secret",
            playbackEpisodes = listOf(PlaybackEpisode(1, "第1集",
                listOf(PlaybackLine("spider-1", "夸父原", "http://127.0.0.1/?secret=$secret")))))
        TvBoxCatalogSnapshot(context).save(root, site, listOf(original))
        val restored = TvBoxCatalogSnapshot(context).load(root, site).single()
        assertEquals("飞行家", restored.title)
        assertEquals("https://image.tmdb.org/t/p/original/backdrop.jpg", restored.backdropUrl)
        assertNull(restored.artworkUrl)
        assertNull(restored.spiderVodId)
        assertNull(restored.cmsVodId)
        assertTrue(restored.playbackEpisodes.isEmpty())
        assertTrue(TvBoxCatalogSnapshot(context).load(root, "different-site").isEmpty())
    }
}