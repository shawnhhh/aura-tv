package tv.herrin.app

import androidx.media3.common.Player

/**
 * In-memory playback journey diagnostics. Only finite stage codes, elapsed time,
 * selected track types and video frame counters are accepted; stream URLs,
 * headers, cookies, media IDs and throwable messages never enter this object.
 *
 * A READY state alone is not proof of visible video. The first decoded/rendered
 * output buffer is tracked separately, and audio-only media is not called a failure.
 */
internal class PlaybackJourneyTrace(private val stalledAfterMs: Long = 15_000L) {
    private var startedAtMs = 0L
    private var readyAtMs: Long? = null
    private var firstFrameAtMs: Long? = null
    private var frameBaseline = 0
    private var state = Player.STATE_IDLE
    private var active = 0
    private var total = 1
    private var attempts = 0
    private var selectedVideo = false
    private var selectedAudio = false
    private var tracksKnown = false
    private var lastFailure: PlaybackFailureHints.Category? = null
    private var failed: PlaybackFailureHints.Category? = null

    fun begin(candidateIndex: Int, candidateCount: Int, nowMs: Long, renderedFrames: Int = 0) {
        active = candidateIndex.coerceAtLeast(0)
        total = candidateCount.coerceAtLeast(1)
        startedAtMs = nowMs.coerceAtLeast(0L)
        readyAtMs = null
        firstFrameAtMs = null
        frameBaseline = renderedFrames.coerceAtLeast(0)
        state = Player.STATE_IDLE
        selectedVideo = false
        selectedAudio = false
        tracksKnown = false
        failed = null
        attempts++
    }

    fun onState(playbackState: Int, nowMs: Long) {
        state = playbackState
        if (playbackState == Player.STATE_READY && readyAtMs == null)
            readyAtMs = nowMs.coerceAtLeast(startedAtMs)
    }

    fun onTracks(video: Boolean, audio: Boolean) {
        tracksKnown = true
        selectedVideo = video
        selectedAudio = audio
    }

    fun onFrames(renderedFrames: Int, nowMs: Long) {
        val current = renderedFrames.coerceAtLeast(0)
        // Decoder counters may restart after a new media item or decoder reset.
        if (current < frameBaseline) {
            frameBaseline = current
            return
        }
        if (current > frameBaseline && firstFrameAtMs == null)
            firstFrameAtMs = nowMs.coerceAtLeast(startedAtMs)
    }

    fun onFailure(category: PlaybackFailureHints.Category) {
        failed = category
        lastFailure = category
    }

    fun summary(nowMs: Long): String {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L) / 1000L
        val last = lastFailure?.let { " · 前次异常：${name(it)}" }.orEmpty()
        val attempt = "第 ${active + 1}/$total 条地址 · 第 $attempts 次尝试"
        val tracks = if (!tracksKnown) "轨道未确认" else when {
            selectedVideo && selectedAudio -> "已选择视频和音频轨道"
            selectedVideo -> "已选择视频轨道"
            selectedAudio -> "仅识别到音频轨道"
            else -> "没有已选中的音视频轨道"
        }
        val status = when {
            failed != null -> "失败位置：${name(failed!!)}"
            firstFrameAtMs != null -> "已输出视频帧 · 首帧耗时 ${(firstFrameAtMs!! - startedAtMs) / 1000L} 秒"
            state == Player.STATE_ENDED -> "播放已结束（没有本次视频首帧证据）"
            state == Player.STATE_READY && selectedAudio && !selectedVideo ->
                "已就绪，只有音频轨道（不应误判为视频黑屏）"
            state == Player.STATE_READY && selectedVideo &&
                nowMs - (readyAtMs ?: startedAtMs) >= stalledAfterMs ->
                "视频轨已就绪超过 ${stalledAfterMs / 1000L} 秒，但尚无视频出帧证据"
            state == Player.STATE_READY -> "播放器已就绪，等待首帧确认"
            state == Player.STATE_BUFFERING && nowMs - startedAtMs >= stalledAfterMs ->
                "持续缓冲 $elapsed 秒，需检查网络或清单/分段请求"
            state == Player.STATE_BUFFERING -> "正在缓冲 $elapsed 秒"
            state == Player.STATE_IDLE -> "准备播放请求"
            else -> "正在解析并准备媒体 $elapsed 秒"
        }
        return "$attempt · $tracks · $status$last"
    }

    private fun name(category: PlaybackFailureHints.Category): String = when (category) {
        PlaybackFailureHints.Category.AUTHENTICATION -> "HTTP 身份验证"
        PlaybackFailureHints.Category.FORBIDDEN -> "HTTP 访问拒绝"
        PlaybackFailureHints.Category.NOT_FOUND -> "媒体地址失效"
        PlaybackFailureHints.Category.RATE_LIMIT -> "请求频率限制"
        PlaybackFailureHints.Category.SERVER -> "服务器错误"
        PlaybackFailureHints.Category.HTTP -> "HTTP 请求"
        PlaybackFailureHints.Category.NETWORK -> "网络连接"
        PlaybackFailureHints.Category.NETWORK_POLICY -> "Android 网络策略"
        PlaybackFailureHints.Category.MANIFEST -> "播放清单解析"
        PlaybackFailureHints.Category.CONTAINER -> "媒体容器解析"
        PlaybackFailureHints.Category.DECODER -> "解码器"
        PlaybackFailureHints.Category.AUDIO_OUTPUT -> "音频输出"
        PlaybackFailureHints.Category.LIVE_WINDOW -> "直播窗口"
        PlaybackFailureHints.Category.UNKNOWN -> "未知类别"
    }
}
