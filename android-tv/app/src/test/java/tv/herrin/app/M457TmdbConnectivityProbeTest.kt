package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class M457TmdbConnectivityProbeTest {
    private val goodToken = "A".repeat(120)

    @Test fun validTmdbV4TokenNeedsActualSearchRouteNotJustConfiguration() {
        val urls = mutableListOf<String>()
        val result = TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, token ->
            assertEquals(goodToken, token)
            urls += url
            when {
                url.endsWith("/3/configuration") ->
                    """{"images":{"base_url":"https://image.tmdb.org/t/p/"}}"""
                url.contains("/3/search/movie?query=Inception") ->
                    """{"results":[{"id":27205,"title":"盗梦空间"}]}"""
                else -> error("Unexpected route")
            }
        })
        assertEquals(TmdbProbeState.CONNECTED, result)
        assertEquals(2, urls.size)
        assertTrue(urls[1].contains("language=zh-CN"))
    }

    @Test fun configurationConnectedButSearchUnavailableCannotReportSuccess() {
        assertEquals(TmdbProbeState.SEARCH_UNAVAILABLE,
            TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, _ ->
                if (url.endsWith("/3/configuration")) """{"images":{}}""" else null
            }))
        assertEquals(TmdbProbeState.SEARCH_UNAVAILABLE,
            TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, _ ->
                if (url.endsWith("/3/configuration")) """{"images":{}}""" else """{"error":"blocked"}"""
            }))
        assertEquals(TmdbProbeState.RATE_LIMITED,
            TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, _ ->
                if (url.endsWith("/3/configuration")) """{"images":{}}"""
                else throw ArtworkRateLimitFailure()
            }))
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
