package tv.herrin.app

/** Local diagnostics contain only allowlisted codes/categories, never request or exception text. */
internal object PlaybackFailureHints {
    enum class Category {
        AUTHENTICATION, FORBIDDEN, NOT_FOUND, RATE_LIMIT, SERVER, HTTP,
        NETWORK, NETWORK_POLICY, MANIFEST, CONTAINER, DECODER, AUDIO_OUTPUT, LIVE_WINDOW, UNKNOWN
    }

    fun safeCode(errorCodeName: String): String =
        errorCodeName.takeIf { it.matches(Regex("ERROR_CODE_[A-Z0-9_]{1,72}")) }
            ?: "ERROR_CODE_UNKNOWN"

    fun safeHttpStatus(status: Int?): Int? = status?.takeIf { it in 100..599 }

    /** Bound traversal even for custom cyclic exception chains returned by a media plugin. */
    fun causes(error: Throwable): List<Throwable> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && result.size < 16 && seen.add(current)) {
            result.add(current)
            current = current.cause
        }
        return result
    }

    fun category(errorCodeName: String, httpStatus: Int?): Category {
        val code = safeCode(errorCodeName)
        return when (safeHttpStatus(httpStatus)) {
            401 -> Category.AUTHENTICATION
            403 -> Category.FORBIDDEN
            404, 410 -> Category.NOT_FOUND
            429 -> Category.RATE_LIMIT
            in 500..599 -> Category.SERVER
            in 400..499 -> Category.HTTP
            else -> when {
                code.contains("CLEARTEXT_NOT_PERMITTED") || code.contains("NO_PERMISSION") -> Category.NETWORK_POLICY
                code.contains("NETWORK_CONNECTION") || code.contains("TIMEOUT") -> Category.NETWORK
                code.contains("PARSING_MANIFEST") -> Category.MANIFEST
                code.contains("PARSING") || code.contains("CONTAINER") -> Category.CONTAINER
                code.contains("DECODING") || code.contains("DECODER") -> Category.DECODER
                code.contains("AUDIO_TRACK") -> Category.AUDIO_OUTPUT
                code.contains("BEHIND_LIVE_WINDOW") -> Category.LIVE_WINDOW
                code.contains("FILE_NOT_FOUND") -> Category.NOT_FOUND
                code.contains("HTTP") -> Category.HTTP
                else -> Category.UNKNOWN
            }
        }
    }

    fun hint(errorCodeName: String, httpStatus: Int?): String = when (category(errorCodeName, httpStatus)) {
        Category.AUTHENTICATION -> "服务器要求身份验证（401）；核对本线路授权及会话是否过期"
        Category.FORBIDDEN -> "服务器拒绝访问（403）；核对本线路权限、Referer/Cookie 和地址是否过期"
        Category.NOT_FOUND -> "播放地址不存在或已失效；尝试更新影视源或切换线路"
        Category.RATE_LIMIT -> "服务器请求次数受限（429）；已停止自动切换，请稍后再试"
        Category.SERVER -> "播放服务器暂时异常；可稍后再试或更换来源"
        Category.HTTP -> "服务器无法完成媒体请求；对照 HTTP 状态核对授权来源"
        Category.NETWORK_POLICY -> "Android 网络安全或访问权限策略阻止请求；核对来源配置"
        Category.NETWORK -> "网络连接失败或超时；核对电视联网、DNS 和影视站点"
        Category.MANIFEST -> "HLS/DASH 播放清单无法解析；核对清单格式和有效性"
        Category.CONTAINER -> "媒体容器格式无法解析；核对合法直链的格式和完整性"
        Category.DECODER -> "设备解码失败；核对视频/音频编码与 TCL 硬件能力"
        Category.AUDIO_OUTPUT -> "音频轨道初始化失败；核对音频编码与电视音频输出能力"
        Category.LIVE_WINDOW -> "直播窗口已变化；重试获取最新直播片段"
        Category.UNKNOWN -> "播放暂时失败；可记录此错误代码并核对授权来源"
    }
}
