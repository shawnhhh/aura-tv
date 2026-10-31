package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureHintsTest {
    @Test fun networkConnectionFailureIdentifiesActualCauseWithoutExposingPrivateUrl() {
        val errors = listOf(
            java.net.UnknownHostException("private.example?token=SECRET") to "DNS",
            java.net.SocketTimeoutException("private.example?token=SECRET") to "超时",
            javax.net.ssl.SSLHandshakeException("private.example?token=SECRET") to "HTTPS",
            java.net.ConnectException("private.example?token=SECRET") to "拒绝连接"
        )
        errors.forEach { (cause, expected) ->
            val error = RuntimeException("wrapper", cause)
            val hint = PlaybackFailureHints.networkHint("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                null, error, "https://private.example/watch?token=SECRET")
            assertNotNull(hint)
            assertTrue("$expected missing in: $hint", hint!!.contains(expected))
            assertFalse(hint.contains("SECRET"))
            assertFalse(hint.contains("private.example"))
        }
    }

    @Test fun refusedLocalSpiderProxyIsDifferentFromRemoteServerAndDoesNotLeakSession() {
        val error = RuntimeException(java.net.ConnectException("Connection refused"))
        val hint = PlaybackFailureHints.networkHint("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            null, error, "http://127.0.0.1:48591/proxy?session=private-session")
        assertTrue(hint.orEmpty().contains("Spider"))
        assertFalse(hint.orEmpty().contains("private-session"))
        assertNull(PlaybackFailureHints.networkHint("ERROR_CODE_IO_BAD_HTTP_STATUS",
            401, error, "http://127.0.0.1:48591/proxy?session=private-session"))
    }

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

    @Test fun httpStatusAndMediaFailureHaveDistinctStableCategories() {
        val code = "ERROR_CODE_IO_BAD_HTTP_STATUS"
        val cases = mapOf(401 to PlaybackFailureHints.Category.AUTHENTICATION,
            403 to PlaybackFailureHints.Category.FORBIDDEN, 404 to PlaybackFailureHints.Category.NOT_FOUND,
            429 to PlaybackFailureHints.Category.RATE_LIMIT, 500 to PlaybackFailureHints.Category.SERVER,
            503 to PlaybackFailureHints.Category.SERVER)
        cases.forEach { (status, expected) -> assertEquals(expected, PlaybackFailureHints.category(code, status)) }
        assertNotEquals(PlaybackFailureHints.hint(code, 401), PlaybackFailureHints.hint(code, 403))
        assertEquals(PlaybackFailureHints.Category.MANIFEST,
            PlaybackFailureHints.category("ERROR_CODE_PARSING_MANIFEST_MALFORMED", null))
        assertEquals(PlaybackFailureHints.Category.CONTAINER,
            PlaybackFailureHints.category("ERROR_CODE_PARSING_CONTAINER_MALFORMED", null))
        assertEquals(PlaybackFailureHints.Category.NETWORK,
            PlaybackFailureHints.category("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", null))
        assertEquals(PlaybackFailureHints.Category.NETWORK_POLICY,
            PlaybackFailureHints.category("ERROR_CODE_IO_NO_PERMISSION", null))
        assertNull(PlaybackFailureHints.safeHttpStatus(999))
    }

    @Test fun exceptionTraversalIsCycleSafeAndBoundedWithoutIncludingMessages() {
        val first = object : Throwable("Cookie: private; token=secret") {
            override val cause: Throwable get() = this
        }
        assertEquals(listOf(first), PlaybackFailureHints.causes(first))
        var nested: Throwable = IllegalStateException("https://secret.invalid/?token=secret")
        repeat(30) { nested = RuntimeException(nested) }
        assertEquals(16, PlaybackFailureHints.causes(nested).size)
        for (category in PlaybackFailureHints.Category.entries) {
            assertFalse(category.name.contains("secret"))
        }
    }
}
