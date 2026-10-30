package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class M457TmdbConnectivityProbeTest {
    private val goodToken = "A".repeat(120)

    @Test fun validTmdbV4TokenAndNetworkAreSeparateFromMatchingResults() {
        var calls = 0
        val result = TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, token ->
            assertTrue(url.endsWith("/3/configuration"))
            assertEquals(goodToken, token)
            calls++
            """{"images":{"base_url":"https://image.tmdb.org/t/p/"}}"""
        })
        assertEquals(TmdbProbeState.CONNECTED, result)
        assertEquals(1, calls)
    }

    @Test fun wrongFormatNeverMakesARequest() {
        val result = TmdbConnectivityProbe.check("1234", ArtworkJsonFetcher { _, _ ->
            fail("Do not send malformed token")
            null
        })
        assertEquals(TmdbProbeState.INVALID_FORMAT, result)
        assertEquals(TmdbProbeState.NOT_CONFIGURED, TmdbConnectivityProbe.check(""))
    }

    @Test fun unauthorizedTokenReportsAuthenticationRatherThanZeroMatchedMovies() {
        assertEquals(TmdbProbeState.UNAUTHORIZED,
            TmdbConnectivityProbe.check(goodToken,
                ArtworkJsonFetcher { _, _ -> throw ArtworkAuthenticationFailure() }))
    }

    @Test fun dnsConnectionFailureIsNotCalledTitleMismatch() {
        assertEquals(TmdbProbeState.CONNECTION_FAILED,
            TmdbConnectivityProbe.check(goodToken,
                ArtworkJsonFetcher { _, _ -> throw UnknownHostException("no network") }))
        assertEquals(TmdbProbeState.SERVER_UNAVAILABLE,
            TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { _, _ -> null }))
    }

    @Test fun outputDoesNotExposePersonalToken() {
        val status = TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { _, _ -> null })
        assertFalse(status.text.contains(goodToken))
    }
}
