package tv.herrin.app

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Visible only after a real playback failure. Report observed error codes
 * and known exception *types*, never untrusted exception messages, URLs,
 * hostnames, cookies, auth headers or raw Spider/third-party responses.
 */
internal object PlaybackErrorReport {
    fun media(
        errorCodeName: String,
        numericCode: Int,
        httpStatus: Int?,
        error: Throwable,
        failedUrl: String,
        attempt: Int,
        total: Int
    ): String {
        val status = PlaybackFailureHints.safeHttpStatus(httpStatus)
        val advice = PlaybackFailureHints.networkHint(errorCodeName, status, error, failedUrl)
            ?: PlaybackFailureHints.hint(errorCodeName, status)
        val cause = knownCause(error)
        val phase = when (PlaybackFailureHints.category(errorCodeName, status)) {
            PlaybackFailureHints.Category.MANIFEST, PlaybackFailureHints.Category.CONTAINER ->
                "Media3 清单/容器解析"
            PlaybackFailureHints.Category.DECODER, PlaybackFailureHints.Category.AUDIO_OUTPUT ->
                "Media3 解码/音频输出"
            PlaybackFailureHints.Category.LIVE_WINDOW -> "Media3 直播时间窗口"
            else -> "Media3 网络/媒体读取"
        }
        return buildString {
            append("失败阶段：").append(phase)
            append("\n诊断分类：").append(FongMiPlaybackRecovery.classify(
                errorCodeName, status, error, failedUrl).kind.name)
            append("\n实际错误：").append(PlaybackFailureHints.safeCode(errorCodeName))
            if (numericCode in 0..99_999) append("（").append(numericCode).append("）")
            if (status != null) append("\nHTTP ").append(status).append("（实际状态码）")
            if (cause != null) append("\n底层异常：").append(cause)
            append("\n已尝试播放地址：").append(attempt.coerceIn(1, total.coerceAtLeast(1)))
                .append('/').append(total.coerceIn(1, 99))
            append("\n原因与建议：").append(advice)
        }
    }

    private fun knownCause(error: Throwable): String? {
        val chain = PlaybackFailureHints.causes(error)
        return when {
            chain.any { it is UnknownHostException } -> "UnknownHostException（DNS 解析失败）"
            chain.any { it is javax.net.ssl.SSLHandshakeException } -> "SSLHandshakeException（TLS 握手失败）"
            chain.any { it is SSLException } -> "SSLException（TLS 通信失败）"
            chain.any { it is SocketTimeoutException } -> "SocketTimeoutException（连接/读取超时）"
            chain.any { it is ConnectException } -> "ConnectException（TCP 连接被拒绝）"
            chain.any { it is NoRouteToHostException } -> "NoRouteToHostException（无网络路由）"
            chain.any { it is EOFException } -> "EOFException（媒体响应提前中断）"
            chain.any { it is SocketException } -> "SocketException（连接中断）"
            else -> null
        }
    }

    /** Source errors happen before Media3 exists, so never invent a Media3 code. */
    fun source(stage: String, reason: String?): String {
        val safeStage = when (stage) {
            "SPIDER" -> "Spider 插件/选集解析"
            "JSON" -> "TVBox JSON 解析接口"
            "BROWSER" -> "FongMi 网页解析器"
            "CMS" -> "CMS 影视源详情/线路"
            else -> "影视源播放地址获取"
        }
        val raw = reason.orEmpty()
        // HTTP codes must be emitted by one of our own parser implementations.
        // Never mine arbitrary Spider messages for an apparent HTTP status.
        val http = if (stage == "JSON" || stage == "BROWSER")
            Regex("HTTP[ （:：]+([1-5]\\d{2})").find(raw)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..599 }
        else null
        val webViewCode = if (stage == "BROWSER")
            Regex("错误 (-?[0-9]{1,4})").find(raw)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -9999..9999 }
        else null
        val proxyHealth = Regex("PROXY_(?:NOT_RUNNING|INVALID_ROUTE|NOT_CATVOD|INVALID_SESSION|INVALID_PORT|HTTP_403|HTTP_404|HTTP_5XX|HTTP_OTHER|CONNECT_REFUSED|TIMEOUT|IO_MAIN_THREAD|IO_CONNECT|IO_SOCKET|IO_POLICY|IO_TLS|IO_STREAM|IO_UNKNOWN|IO_FAILURE)")
            .find(raw)?.value
        val routeReason = Regex("route_reason=(INVALID_ALIAS|QUERY_TOO_LONG|INVALID_QUERY|INVALID_PLAYER_JSON|MALFORMED_URI|NOT_LOCAL_PROXY|NOT_PROXY_PATH|UNSUPPORTED_OPERATION|SITE_KEY_MISMATCH|STATIC_MARKER_FORGED|TOO_MANY_PARAMETERS|ALIAS_REJECTED|LOOPBACK_ROUTE_REJECTED|MISSING_PLAYER_URL|PROXY_STOPPED|EMPTY_URL)")
            .find(raw)?.groupValues?.get(1)
        val safeOperation = Regex("route_operation=(JAR|CSP|LIVE|PUSH|CZSpp|CZSPP|CK|PARSE|PLAY|PROXY|VOD|M3U8|OTHER|MISSING|UNAVAILABLE)")
            .find(raw)?.groupValues?.get(1)
        val mediaProbe = Regex("MEDIA_PROBE_(?:INVALID_URL|UNAUTHORIZED_LOCAL|HTTP_ERROR|LOGIN_OR_TEXT|INVALID_RANGE|EMPTY_BODY|INVALID_MANIFEST|NETWORK_ERROR|PROBE_TIMEOUT)(?![A-Z_])").find(raw)?.value
        val parserCode = Regex("PARSE_(?:WEB_REQUIRED|NO_MEDIA_URL|INVALID_JSON|INVALID_RESULT_URL|INVALID_RESULT|INVALID_URL|TIMEOUT|DNS|CONNECT|TLS|JAR_CLASS|JAR_ABI|HTTP|FAILED)(?![A-Z_])").find(raw)?.value
        val kind = when {
            mediaProbe != null -> "SOURCE_$mediaProbe"
            parserCode != null -> "SOURCE_$parserCode"
            proxyHealth != null -> "SOURCE_$proxyHealth"
            http != null -> "SOURCE_HTTP_$http"
            webViewCode != null -> "SOURCE_WEBVIEW_$webViewCode"
            raw.contains("初始化失败") || raw.contains("插件初始化") -> "SOURCE_SPIDER_INITIALIZATION"
            raw.contains("DNS", ignoreCase = true) || raw.contains("域名解析失败") ->
                "SOURCE_DNS_FAILED"
            raw.contains("TLS", ignoreCase = true) || raw.contains("证书") ->
                "SOURCE_TLS_FAILED"
            raw.contains("超时") -> "SOURCE_TIMEOUT"
            raw.contains("异常退出") || raw.contains("进程退出") -> "SOURCE_PROCESS_EXITED"
            raw.contains("服务已断开") -> "SOURCE_DISCONNECTED"
            raw.contains("网页解析") || raw.contains("网页") -> "SOURCE_WEB_PARSE_FAILED"
            raw.contains("JSON") && raw.contains("不是 JSON") -> "SOURCE_INVALID_JSON"
            raw.contains("JSON") -> "SOURCE_JSON_PARSE_FAILED"
            raw.contains("授权") || raw.contains("密码") || raw.contains("登录") ->
                "SOURCE_AUTH_REQUIRED"
            raw.contains("没有可用选集") -> "SOURCE_NO_EPISODES"
            raw.contains("CMS 详情读取失败") -> "SOURCE_CMS_DETAIL_FAILED"
            raw.contains("播放解析未返回结果") -> "SOURCE_NO_RESOLVED_MEDIA"
            raw.contains("代理") -> "SOURCE_PROXY_UNAVAILABLE"
            raw.contains("HTTP(S) 直链") || raw.contains("媒体地址") || raw.contains("播放地址") ->
                "SOURCE_NO_MEDIA_URL"
            else -> "SOURCE_UNCLASSIFIED"
        }
        return buildString {
            val probeFailure = proxyHealth?.matches(Regex("PROXY_(?:IO_[A-Z_]+|CONNECT_REFUSED|TIMEOUT|HTTP_[A-Z0-9]+|INVALID_SESSION|INVALID_PORT)")) == true
            append("失败阶段：").append(if (mediaProbe != null) "媒体响应字节验证" else if (probeFailure) "本机 Spider 代理健康检查" else safeStage)
            append("\n诊断分类：").append(kind)
            if (routeReason != null) append("\n代理路由校验：").append(routeReason)
            if (safeOperation != null) append("\n代理操作类型：").append(safeOperation)
            if (http != null) append("\nHTTP ").append(http).append("（实际状态码）")
            if (kind == "SOURCE_UNCLASSIFIED")
                append("\n来源未提供可安全识别的错误码；未进入 Media3")
        }
    }
}
