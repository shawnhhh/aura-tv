package tv.herrin.app

import androidx.media3.common.Player

/** Privacy-safe, local diagnostics for user-authorized playback. */
internal object PlaybackAcceptance {
    fun phase(state: Int, renderedFrames: Int, error: String?): String = when {
        error != null -> "播放异常"
        state == Player.STATE_BUFFERING -> "正在缓冲"
        state == Player.STATE_READY && renderedFrames > 0 -> "视频已出帧"
        state == Player.STATE_READY -> "已就绪，等待视频帧"
        state == Player.STATE_ENDED -> "播放结束"
        else -> "正在准备"
    }

    fun summary(
        state: Int, renderedFrames: Int, droppedFrames: Int,
        width: Int, height: Int, audioTracks: Int, subtitleTracks: Int,
        error: String?
    ): String = buildString {
        append("实播状态：${phase(state, renderedFrames, error)}")
        append(" · 画面：")
        append(if (width > 0 && height > 0) "${width}×${height}" else "待识别")
        append(" · 已输出视频帧：${renderedFrames.coerceAtLeast(0)}")
        append(" · 丢帧：${droppedFrames.coerceAtLeast(0)}")
        append(" · 音轨：${audioTracks.coerceAtLeast(0)}")
        append(" · 字幕轨：${subtitleTracks.coerceAtLeast(0)}")
    }
}
