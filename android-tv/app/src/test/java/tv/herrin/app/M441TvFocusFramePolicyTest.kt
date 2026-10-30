package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M441TvFocusFramePolicyTest {
    @Test fun filmWallDefersOnlyArtworkNotInputOrPlayback() {
        assertTrue(TvFocusFramePolicy.delaysBackground("browse"))
        assertTrue(TvFocusFramePolicy.delaysBackground("search"))
        assertFalse(TvFocusFramePolicy.delaysBackground("detail"))
        assertFalse(TvFocusFramePolicy.delaysBackground("player"))
        assertFalse(TvFocusFramePolicy.delaysBackground("live-player"))
        assertTrue(TvFocusFramePolicy.HERO_DWELL_MS in 120L..300L)
        assertTrue(TvFocusFramePolicy.HERO_CROSSFADE_MS in 0..200)
        assertTrue(TvFocusFramePolicy.HERO_DWELL_MS >
            TvFocusFramePolicy.HERO_ARTWORK_DOWNLOAD_DWELL_MS)
    }

    @Test fun videoRenderingCannotCompeteWithUnneededHomeBackdrop() {
        assertFalse(TvFocusFramePolicy.allowsBackdrop("player"))
        assertFalse(TvFocusFramePolicy.allowsBackdrop("live-player"))
        assertTrue(TvFocusFramePolicy.allowsBackdrop("browse"))
        assertTrue(TvFocusFramePolicy.allowsBackdrop("search"))
        assertTrue(TvFocusFramePolicy.allowsBackdrop("detail"))
    }

    @Test fun seasonAndPersistentProgressReadsAreOutsidePosterFocusHotPath() {
        for (screen in listOf("browse", "search", "settings", "sites", "live-player")) {
            assertFalse(TvFocusFramePolicy.needsPlaybackSelectionData(screen))
            assertFalse(TvFocusFramePolicy.shouldLoadSeasonDetails(screen))
        }
        assertTrue(TvFocusFramePolicy.needsPlaybackSelectionData("player"))
        assertTrue(TvFocusFramePolicy.needsPlaybackSelectionData("detail"))
        assertTrue(TvFocusFramePolicy.shouldLoadSeasonDetails("detail"))
        assertFalse(TvFocusFramePolicy.shouldLoadSeasonDetails("player"))
    }
}
