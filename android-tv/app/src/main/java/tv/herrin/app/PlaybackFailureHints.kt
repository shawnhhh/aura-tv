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

    /**
     * Network failures often have the same Media3 code, but very different
     * remedies. Identify the root cause without ever exposing media URL,
     * tokens, headers, private hostnames or provider exception messages.
     */
    fun networkHint(errorCodeName: String, httpStatus: Int?, error: Throwable,
                    failedUrl: String): String? {
        if (category(errorCodeName, httpStatus) != Category.NETWORK || httpStatus != null)
            return null
        val host = runCatching { java.net.URI(failedUrl).host?.lowercase() }.getOrNull()
        val localSpider = host in setOf("127.0.0.1", "::1", "[::1]", "localhost")
        val failures = causes(error)
        return when {
            failures.any { it is java.net.UnknownHostException } ->
                "播放服务器域名解析失败（DNS）；请检查电视网络的 DNS 或切换线路"
            failures.any { it is javax.net.ssl.SSLHandshakeException ||
                    it is java.security.cert.CertificateException } ->
                "HTTPS 握手或证书验证失败；请检查电视系统时间与来源的 HTTPS 设置"
            failures.any { it is java.net.ConnectException } ->
                if (localSpider)
                    "本机 Spider 播放代理无法连接；请重新应用该影视站点，再选择集数播放"
                else "播放服务器拒绝连接或目标端口不可用；请切换播放线路"
            failures.any { it is java.net.NoRouteToHostException } ->
                "电视无法找到播放服务器的网络路由；请检查网络或切换线路"
            failures.any { it is java.net.SocketTimeoutException } ->
                if (localSpider)
                    "本机 Spider 代理响应超时；请重新应用该影视站点"
                else "播放服务器响应超时；请检查网络后再试或切换线路"
            failures.any { it is java.net.SocketException } ->
                if (localSpider) "本机 Spider 代理连接已断开；请重新应用该影视站点"
                else "播放服务器中断了网络连接；请重试或切换线路"
            else -> if (localSpider)
                "本机 Spider 播放代理未响应；请重新应用该影视站点"
            else "播放服务器连接失败；请核对影视源、电视网络或切换线路"
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
