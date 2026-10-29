package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/** Regression for TCL remote behavior: Up/Down is a programme change, not a line-selection step. */
class M420LiveRemoteAcceptanceTest {
    private val tvChannels = listOf(
        LiveChannel("CCTV1", "https://test.example/cctv1-low.m3u8", "央视"),
        LiveChannel("CCTV-1 高清", "https://test.example/cctv1-hi.m3u8", "央视"),
        LiveChannel("CCTV2", "https://test.example/cctv2-low.m3u8", "央视"),
        LiveChannel("CCTV-2 高清", "https://test.example/cctv2-hi.m3u8", "央视"),
        LiveChannel("CCTV3", "https://test.example/cctv3.m3u8", "央视")
    )

    @Test fun remoteDownChangesProgrammeAndStartsDefaultLineWithoutPicker() {
        val next = LiveChannelNavigation.move(tvChannels, tvChannels[0].url, delta = 1)
        assertEquals("CCTV2", next?.name)
        assertEquals("https://test.example/cctv2-low.m3u8", next?.url)
    }

    @Test fun remoteUpReturnsToPreviousProgrammeNotPreviousLine() {
        val previous = LiveChannelNavigation.move(tvChannels, tvChannels[2].url, delta = -1)
        assertEquals("CCTV1", previous?.name)
        assertEquals(tvChannels[0].url, previous?.url)
    }

    @Test fun previouslyChosenLineIsHonoredWhenRemoteReturnsToAProgramme() {
        val choice = LiveChannelDiscovery.choices(tvChannels)
        assertEquals(3, choice.size)
        val selected = LiveChannelNavigation.move(tvChannels, tvChannels[0].url, delta = 1,
            preferredLineUrls = mapOf("cctv2" to tvChannels[3].url))
        assertEquals(tvChannels[3].url, selected?.url)
    }

    @Test fun remoteChannelSwitchWrapsAtLastProgramme() {
        assertEquals(tvChannels[0].url,
            LiveChannelNavigation.move(tvChannels, tvChannels.last().url, delta = 1)?.url)
        assertEquals(tvChannels.last().url,
            LiveChannelNavigation.move(tvChannels, tvChannels.first().url, delta = -1)?.url)
    }

    @Test fun emptyListDoesNotCrashRemoteNavigation() {
        assertNull(LiveChannelNavigation.move(emptyList(), "", delta = 1))
    }
}
