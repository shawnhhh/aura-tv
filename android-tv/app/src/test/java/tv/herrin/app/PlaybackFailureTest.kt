package tv.herrin.app

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureTest {
    @Test fun friendlyErrorMessagesDoNotExposeUrlOrTechnicalCode() {
        val network = playbackErrorMessage("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
        assertTrue(network.contains("网络"))
        assertTrue(!network.contains("ERROR_CODE"))
        assertTrue(playbackErrorMessage("ERROR_CODE_DECODING_FAILED").contains("视频格式"))
        assertTrue(playbackErrorMessage("ERROR_CODE_BEHIND_LIVE_WINDOW").contains("直播"))
        assertTrue(playbackErrorMessage("unknown").contains("重试播放"))
    }
}
