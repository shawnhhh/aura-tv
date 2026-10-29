package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureHintsTest {
    @Test fun `HTTP error advice identifies authorization, expired URLs, throttling, and server outage`() {
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_IO_BAD_HTTP_STATUS", 403).contains("服务器拒绝"))
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_IO_BAD_HTTP_STATUS", 404).contains("失效"))
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_IO_BAD_HTTP_STATUS", 429).contains("受限"))
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_IO_BAD_HTTP_STATUS", 502).contains("服务器"))
    }

    @Test fun `parsing and decoder failure report separate root cause categories`() {
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_PARSING_CONTAINER_MALFORMED", null)
            .contains("解析"))
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_DECODING_FAILED", null)
            .contains("解码"))
        assertTrue(PlaybackFailureHints.hint("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", null)
            .contains("网络"))
    }

    @Test fun `unsafe error code never echoes URL or signed query in UI`() {
        val secret = "https://secret.example.test/video?token=abcXYZ"
        assertEquals("ERROR_CODE_UNKNOWN",
            PlaybackFailureHints.safeCode("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED $secret"))
        assertFalse(PlaybackFailureHints.hint("ERROR_CODE_OTHER $secret", null).contains(secret))
        assertEquals("ERROR_CODE_DECODING_FAILED",
            PlaybackFailureHints.safeCode("ERROR_CODE_DECODING_FAILED"))
    }
}
