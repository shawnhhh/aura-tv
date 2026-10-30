package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M453PlayerRemoteFocusPolicyTest {
    @Test fun bufferingDoesNotHideOrDelayTransportControls() {
        // The actual decoded-video frame count is intentionally absent from
        // this policy. A player with zero frames still has a focusable overlay.
        assertTrue(PlayerRemoteFocusPolicy.showTransport(
            live = false, controlsVisible = true, errorVisible = false))
        assertEquals(PlayerRemoteFocusPolicy.Destination.TRANSPORT,
            PlayerRemoteFocusPolicy.destination(
                live = false, requested = true,
                controlsVisible = true, errorVisible = false))
    }

    @Test fun playbackFailureRoutesRemoteDownToVisibleRetry() {
        assertFalse(PlayerRemoteFocusPolicy.showTransport(
            live = false, controlsVisible = true, errorVisible = true))
        assertEquals(PlayerRemoteFocusPolicy.Destination.RETRY,
            PlayerRemoteFocusPolicy.destination(
                live = false, requested = true,
                controlsVisible = true, errorVisible = true))
        assertEquals(PlayerRemoteFocusPolicy.Destination.RETRY,
            PlayerRemoteFocusPolicy.destination(
                live = false, requested = true,
                controlsVisible = false, errorVisible = true))
    }

    @Test fun aHiddenOverlayDoesNotCaptureFocusWithoutAnError() {
        assertEquals(PlayerRemoteFocusPolicy.Destination.NONE,
            PlayerRemoteFocusPolicy.destination(
                live = false, requested = true,
                controlsVisible = false, errorVisible = false))
        assertEquals(PlayerRemoteFocusPolicy.Destination.NONE,
            PlayerRemoteFocusPolicy.destination(
                live = false, requested = false,
                controlsVisible = true, errorVisible = false))
    }

    @Test fun liveChannelKeepsItsExistingDrawerFocusModel() {
        assertFalse(PlayerRemoteFocusPolicy.showTransport(
            live = true, controlsVisible = true, errorVisible = false))
        assertEquals(PlayerRemoteFocusPolicy.Destination.NONE,
            PlayerRemoteFocusPolicy.destination(
                live = true, requested = true,
                controlsVisible = true, errorVisible = true))
    }
}
