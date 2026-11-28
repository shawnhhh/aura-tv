package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TmdbDiscoveryPlayIntentTest {
    @Test fun backgroundMetadataNeverCreatesPlaybackIntent() {
        assertFalse(TmdbScreenState().consumeDiscoveryPlay("film","node:vod",0))
    }
    @Test fun onlyCurrentExplicitNodeAndEpochMayStartPlaybackOnce() {
        val state = TmdbScreenState()
        state.discoverySelectionEpoch = 4
        state.requestDiscoveryPlay("film","node:vod")
        assertFalse(state.consumeDiscoveryPlay("film","other:vod",4))
        assertFalse(state.consumeDiscoveryPlay("other","node:vod",4))
        assertFalse(state.consumeDiscoveryPlay("film","node:vod",3))
        assertTrue(state.consumeDiscoveryPlay("film","node:vod",4))
        assertFalse(state.consumeDiscoveryPlay("film","node:vod",4))
    }
    @Test fun cancelledOldSelectionCannotClearOrStartNewSelection() {
        val state = TmdbScreenState()
        state.requestDiscoveryPlay("film","first")
        state.discoverySelectionEpoch++
        state.requestDiscoveryPlay("film","second")
        state.discardDiscoveryPlay("film","first",0)
        assertFalse(state.consumeDiscoveryPlay("film","first",0))
        assertTrue(state.consumeDiscoveryPlay("film","second",1))
    }
}
