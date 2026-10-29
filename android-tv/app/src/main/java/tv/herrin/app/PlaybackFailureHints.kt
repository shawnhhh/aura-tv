package tv.herrin.app

/**
 * Local, privacy-safe diagnostics for playback failures. Do not log or display stream URLs,
 * cookies, user-agent strings, signed query strings, or response bodies.
 */
internal object PlaybackFailureHints {
    fun safeCode(errorCodeName: String): String =
        errorCodeName.takeIf { it.matches(Regex("ERROR_CODE_[A-Z0-9_]{1,72}")) }
            ?: "ERROR_CODE_UNKNOWN"

    fun hint(errorCodeName: String, httpStatus: Int?): String {
        val code = safeCode(errorCodeName)
        return when {
            httpStatus == 401 || httpStatus == 403 ->
                "服务器拒绝访问；核对本线路授权、Referer/Cookie 和地址是否过期"
            httpStatus == 404 || httpStatus == 410 ->
                "播放地址不存在或已失效；尝试更新影视源或切换线路"
            httpStatus == 429 -> "服务器请求次数受限；稍后再试"
            httpStatus != null && httpStatus in 500..599 ->
                "播放服务器暂时异常；可稍后再试或更换来源"
            code.contains("IO_CLEARTEXT_NOT_PERMITTED") ->
                "当前 Android 网络安全策略不允许该 HTTP 地址"
            code.contains("IO_NETWORK_CONNECTION") || code.contains("IO_NO_PERMISSION") ->
                "网络连接失败；核对电视联网、DNS 和影视站点"
            code.contains("IO_FILE_NOT_FOUND") ->
                "当前媒体地址没有返回可用文件"
            code.contains("PARSING") ->
                "媒体格式或播放清单无法解析；可能需要站点专用解析器"
            code.contains("DECODING") || code.contains("DECODER") ->
                "设备解码失败；核对视频/音频编码与 TCL 硬件能力"
            code.contains("AUDIO_TRACK") -> "音频轨道初始化失败；核对音频编码兼容性"
            code.contains("IO_BAD_HTTP_STATUS") ->
                "服务器拒绝或无法完成媒体请求；需对照 HTTP 状态定位"
            else -> "请记录此错误代码，并对照同一线路在 TVBox 的播放结果"
        }
    }
}
