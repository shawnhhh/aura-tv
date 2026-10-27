package tv.herrin.app

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackAcceptanceTest {
    @Test fun distinguishesActualFramesFromMerelyReadyPlayer() {
        assertEquals("正在缓冲", PlaybackAcceptance.phase(Player.STATE_BUFFERING, 0, null))
        assertEquals("已就绪，等待视频帧", PlaybackAcceptance.phase(Player.STATE_READY, 0, null))
        assertEquals("视频已出帧", PlaybackAcceptance.phase(Player.STATE_READY, 9, null))
        assertEquals("播放异常", PlaybackAcceptance.phase(Player.STATE_READY, 20, "HTTP failure"))
    }

    @Test fun diagnosticsNeverNeedTheMediaUrlOrCredentials() {
        val summary = PlaybackAcceptance.summary(Player.STATE_READY, 120, 2,
            1920, 1080, 1, 2, null)
        assertTrue(summary.contains("1920×1080"))
        assertTrue(summary.contains("已输出视频帧：120"))
        assertTrue(summary.contains("丢帧：2"))
        assertFalse(summary.contains("https://"))
        assertTrue(PlaybackAcceptance.summary(Player.STATE_IDLE, -1, -1,
            0, 0, 0, 0, null).contains("待识别"))
    }
}
