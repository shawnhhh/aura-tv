package tv.herrin.app

/** No source URL or response body is embedded in a user-visible playback error. */
internal fun playbackErrorMessage(errorCodeName: String): String = when {
    errorCodeName.contains("NETWORK") || errorCodeName.contains("HTTP") ||
        errorCodeName.contains("TIMEOUT") ->
        "网络或服务器暂时不可用，请确认你有权访问的播放地址后重试"
    errorCodeName.contains("DECOD") || errorCodeName.contains("PARSING") ||
        errorCodeName.contains("CONTAINER") ->
        "该视频格式或字幕无法正常解析，请切换合适的授权线路"
    errorCodeName.contains("BEHIND_LIVE_WINDOW") ->
        "直播窗口已变化，请重试获取最新直播片段"
    else -> "播放暂时失败，可按「重试播放」再次尝试"
}
