package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SpiderProxyDiagnosticsTest {
    @Test fun recorderFailureCannotChangeOriginalProxyResponse() {
        val response = arrayOf<Any>(200, "video/mp4", java.io.ByteArrayInputStream(byteArrayOf(1)))
        val returned = SpiderProxyDiagnostics.observe({ _, _ -> error("recorder failed") }) { response }
        assertSame(response, returned)
    }
    @Test fun recorderFailureCannotReplacePluginException() {
        val original = IllegalStateException("private provider failure")
        try {
            SpiderProxyDiagnostics.observe({ _, _ -> error("recorder failed") }) { throw original }
            fail("Expected original error")
        } catch (observed: IllegalStateException) { assertSame(original, observed) }
    }
    @Test fun shapeReportsNumbersAndTypesWithoutPayloadsHeadersOrExceptionText() {
        val secret = "PRIVATE_TOKEN_SENTINEL"
        val response = arrayOf<Any>(502, "https://media.invalid/?token=$secret", java.io.ByteArrayInputStream(byteArrayOf(1)), mapOf("Cookie" to secret))
        val shape = SpiderProxyDiagnostics.shape(response, null)
        assertEquals(502, shape.getInt("status"))
        assertEquals(4, shape.getInt("length"))
        assertFalse(shape.toString().contains(secret))
        assertFalse(shape.toString().contains("https://"))
        val failure = SpiderProxyDiagnostics.shape(null, IllegalStateException(secret))
        assertEquals("CALLBACK_THROW", failure.getString("outcome"))
        assertFalse(failure.toString().contains(secret))
    }
}
