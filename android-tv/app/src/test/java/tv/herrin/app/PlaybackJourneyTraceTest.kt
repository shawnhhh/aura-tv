package tv.herrin.app

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackJourneyTraceTest {
    @Test fun `ready video track without frames becomes a stalled-first-frame hint after 15s`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 2, 1_000)
        trace.onTracks(video = true, audio = true)
        trace.onState(Player.STATE_READY, 2_000)
        assertTrue(trace.summary(16_999).contains("等待首帧"))
        assertTrue(trace.summary(17_000).contains("尚无视频出帧证据"))
        assertTrue(trace.summary(17_000).contains("第 1/2 条地址"))
    }

    @Test fun `first rendered output buffer is distinguished from player ready`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 1, 1_000)
        trace.onTracks(video = true, audio = true)
        trace.onState(Player.STATE_READY, 2_000)
        assertFalse(trace.summary(2_000).contains("已输出视频帧"))
        trace.onFrames(0, 2_100)
        trace.onFrames(1, 2_500)
        assertTrue(trace.summary(22_000).contains("已输出视频帧"))
        assertTrue(trace.summary(22_000).contains("首帧耗时 1 秒"))
    }

    @Test fun `audio-only track never triggers a false black-screen diagnosis`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 1, 0)
        trace.onTracks(video = false, audio = true)
        trace.onState(Player.STATE_READY, 1_000)
        val result = trace.summary(100_000)
        assertTrue(result.contains("只有音频轨道"))
        assertFalse(result.contains("视频轨已就绪超过"))
    }

    @Test fun `buffering remains separate from decoder readiness`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 1, 5_000)
        trace.onState(Player.STATE_BUFFERING, 5_100)
        assertTrue(trace.summary(7_000).contains("正在缓冲"))
        assertTrue(trace.summary(20_000).contains("持续缓冲"))
        assertFalse(trace.summary(20_000).contains("视频轨已就绪"))
    }

    @Test fun `backup sources reset frame evidence and retain safe last error category`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 3, 1_000, renderedFrames = 40)
        trace.onFrames(41, 2_000)
        assertTrue(trace.summary(2_000).contains("已输出视频帧"))
        trace.onFailure(PlaybackFailureHints.Category.FORBIDDEN)
        trace.begin(1, 3, 3_000, renderedFrames = 41)
        assertTrue(trace.summary(3_100).contains("前次异常：HTTP 访问拒绝"))
        assertFalse(trace.summary(3_100).contains("已输出视频帧"))
        trace.onFrames(0, 3_500) // decoder counter was reset
        assertFalse(trace.summary(3_500).contains("已输出视频帧"))
        trace.onFrames(1, 4_000)
        assertTrue(trace.summary(4_000).contains("已输出视频帧"))
        assertTrue(trace.summary(4_000).contains("第 2/3 条地址"))
        assertTrue(trace.summary(4_000).contains("第 2 次尝试"))
    }

    @Test fun `terminal failure remains a finite category not a URL or token`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 1, 0)
        trace.onFailure(PlaybackFailureHints.Category.MANIFEST)
        val result = trace.summary(50_000)
        assertTrue(result.contains("失败位置：播放清单解析"))
        assertFalse(result.contains("https://"))
        assertFalse(result.contains("token"))
        assertFalse(result.contains("Cookie"))
    }

    @Test fun `explicit retry clears previous failure without faking successful playback`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(0, 1, 5_000)
        trace.onFailure(PlaybackFailureHints.Category.DECODER)
        trace.clearPreviousFailure()
        trace.begin(0, 1, 10_000)
        assertFalse(trace.summary(10_000).contains("解码器"))
        assertFalse(trace.summary(10_000).contains("已输出视频帧"))
    }

    @Test fun `elapsed counters clamp on monotonic rollback and frameCount never negative`() {
        val trace = PlaybackJourneyTrace()
        trace.begin(-1, -10, -500, renderedFrames = -10)
        trace.onTracks(video = true, audio = false)
        trace.onState(Player.STATE_READY, -200)
        trace.onFrames(-1, -1)
        val summary = trace.summary(-10)
        assertTrue(summary.contains("第 1/1 条地址"))
        assertFalse(summary.contains("-1 秒"))
    }
}
