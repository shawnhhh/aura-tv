package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M464SpiderProxyHealthTest {
    @Test fun invalidLocalProxySessionIsReportedNotMisdiagnosedAsDecoderFailure() = runBlocking {
        val noToken = "http://127.0.0.1:9966/proxy?do=jar&siteKey=test"
        assertEquals(SpiderProxyPlayback.Health.INVALID_SESSION,
            SpiderProxyPlayback.diagnose(noToken))
        assertEquals(SpiderProxyPlayback.Health.INVALID_PORT,
            SpiderProxyPlayback.diagnose("http://127.0.0.1:0/proxy?do=jar&siteKey=test&session=" +
                "A".repeat(32)))
        assertEquals(SpiderProxyPlayback.Health.NOT_CATVOD,
            SpiderProxyPlayback.diagnose("https://test.invalid/movie.mp4"))
    }

    @Test fun diagnosticReportsObservedProxyReasonWithoutExposingSession() {
        val result = PlaybackErrorReport.source("SPIDER",
            "本机 Spider 播放代理无法启动（PROXY_CONNECT_REFUSED）secret=PRIVATE")
        assertTrue(result.contains("SOURCE_PROXY_CONNECT_REFUSED"))
        assertFalse(result.contains("PRIVATE"))
        assertFalse(result.contains("ERROR_CODE_DECODING_FAILED"))
    }
}