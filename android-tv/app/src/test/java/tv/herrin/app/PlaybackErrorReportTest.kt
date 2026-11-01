package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PlaybackErrorReportTest {
    private val secret = "https://private.example/watch.m3u8?token=SUPERSENSITIVE"

    @Test fun mediaReportsObservedMedia3AndDnsWithoutRawPrivateSource() {
        val error = IllegalStateException("Bearer SUPERSENSITIVE",
            UnknownHostException("private.example?token=SUPERSENSITIVE"))
        val report = PlaybackErrorReport.media(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 2001, null,
            error, secret, 2, 3)
        assertTrue(report.contains("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertTrue(report.contains("2001"))
        assertTrue(report.contains("UnknownHostException"))
        assertTrue(report.contains("DNS"))
        assertTrue(report.contains("2/3"))
        assertFalse(report.contains("SUPERSENSITIVE"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("Bearer"))
    }

    @Test fun failedHttpResponseKeepsRealStatusAndNotAWebPageUrl() {
        val report = PlaybackErrorReport.media("ERROR_CODE_IO_BAD_HTTP_STATUS",
            2004, 403, RuntimeException("Cookie=SUPERSENSITIVE"), secret, 1, 1)
        assertTrue(report.contains("HTTP 403"))
        assertTrue(report.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertTrue(report.contains("2004"))
        assertFalse(report.contains("SUPERSENSITIVE"))
    }

    @Test fun decoderAndManifestRemainDistinctPhases() {
        val decoder = PlaybackErrorReport.media("ERROR_CODE_DECODING_FAILED", 4003,
            null, IllegalArgumentException("password=SUPERSENSITIVE"), secret, 1, 1)
        val manifest = PlaybackErrorReport.media("ERROR_CODE_PARSING_MANIFEST_MALFORMED",
            3002, null, RuntimeException(), secret, 1, 1)
        assertTrue(decoder.contains("解码"))
        assertTrue(decoder.contains("ERROR_CODE_DECODING_FAILED"))
        assertTrue(manifest.contains("清单"))
        assertFalse(decoder.contains("password"))
    }

    @Test fun tlsAndTimeoutShowActualExceptionTypeWithoutThrowableMessage() {
        val tls = PlaybackErrorReport.media("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            2001, null, SSLHandshakeException(secret), secret, 1, 1)
        val timeout = PlaybackErrorReport.media("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            2002, null, SocketTimeoutException(secret), secret, 1, 1)
        assertTrue(tls.contains("SSLHandshakeException"))
        assertTrue(timeout.contains("SocketTimeoutException"))
        assertFalse(tls.contains(secret))
        assertFalse(timeout.contains(secret))
    }

    @Test fun sourceReportsRealHttpAndWebViewCodesWithoutPassingRawParserResponse() {
        val http = PlaybackErrorReport.source("JSON",
            "JSON 解析接口 HTTP 响应异常（HTTP 403）；Authorization: SUPERSENSITIVE")
        val web = PlaybackErrorReport.source("BROWSER",
            "网页解析入口无法加载（WebView 错误 -2）；$secret")
        assertTrue(http.contains("SOURCE_HTTP_403"))
        assertTrue(http.contains("HTTP 403"))
        assertTrue(web.contains("SOURCE_WEBVIEW_-2"))
        assertFalse(http.contains("SUPERSENSITIVE"))
        assertFalse(web.contains("private.example"))
    }

    @Test fun unknownSpiderFailureSaysUnknownInsteadOfInventingMedia3Code() {
        val report = PlaybackErrorReport.source("SPIDER",
            "opaque third party error: $secret")
        assertTrue(report.contains("SOURCE_UNCLASSIFIED"))
        assertTrue(report.contains("未进入 Media3"))
        assertFalse(report.contains("SUPERSENSITIVE"))
        assertFalse(report.contains("ERROR_CODE_IO"))
    }
}
