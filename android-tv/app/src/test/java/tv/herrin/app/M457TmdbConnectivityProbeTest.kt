package tv.herrin.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

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

    @Test fun realHttp429IsDiagnosedWithoutTreatingItAsBadMovieTitles() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"images":{}}"""))
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status_code":25,"status_message":"Your request count is over the allowed limit."}"""))
            val status = TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { url, token ->
                val uri = java.net.URI(url)
                TmdbEndpointRouter().load(url) { NetworkArtworkFetcher.requestOnce(server.url(uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")).toString(), token) }
            })
            assertEquals(TmdbProbeState.RATE_LIMITED, status)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer $goodToken", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer $goodToken", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun outputDoesNotExposePersonalToken() {
        val status = TmdbConnectivityProbe.check(goodToken, ArtworkJsonFetcher { _, _ -> null })
        assertFalse(status.text.contains(goodToken))
    }
    @Test fun failureReportIdentifiesEndpointAndNetworkClassWithoutCredentialLeak() {
        val report = TmdbConnectivityProbe.checkDetailed(goodToken, ArtworkJsonFetcher { url, _ ->
            if (url.endsWith("/3/configuration")) """{"images":{}}"""
            else throw SocketTimeoutException("request included private credential $goodToken")
        })
        assertEquals(TmdbProbeState.CONNECTION_FAILED, report.state)
        assertEquals("搜索接口", report.stage)
        assertTrue(report.text.contains("超时"))
        assertFalse(report.text.contains(goodToken))
        val dns = TmdbConnectivityProbe.checkDetailed(goodToken,
            ArtworkJsonFetcher { _, _ -> throw UnknownHostException("private $goodToken") })
        assertEquals("配置接口", dns.stage)
        assertTrue(dns.text.contains("DNS"))
        assertFalse(dns.text.contains(goodToken))
        val tls = TmdbConnectivityProbe.checkDetailed(goodToken,
            ArtworkJsonFetcher { _, _ -> throw SSLHandshakeException("private $goodToken") })
        assertTrue(tls.text.contains("TLS"))
        assertFalse(tls.text.contains(goodToken))
    }

    @Test fun http403IsNotMisreportedAsMovieTitleMismatch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val result = TmdbConnectivityProbe.checkDetailed(goodToken, ArtworkJsonFetcher { url, token ->
                val uri = java.net.URI(url)
                TmdbEndpointRouter().load(url) { NetworkArtworkFetcher.requestOnce(server.url(uri.rawPath).toString(), token) }
            })
            assertEquals(TmdbProbeState.UNAUTHORIZED, result.state)
            assertEquals("配置接口", result.stage)
            assertTrue(result.text.contains("HTTP 403"))
            assertFalse(result.text.contains(goodToken))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun http503RetriesOnceAndReportsStatusNotBadToken() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
            val result = TmdbConnectivityProbe.checkDetailed(goodToken, ArtworkJsonFetcher { url, token ->
                val uri = java.net.URI(url)
                TmdbEndpointRouter().load(url) { NetworkArtworkFetcher.requestOnce(server.url(uri.rawPath).toString(), token) }
            })
            assertEquals(TmdbProbeState.SERVER_UNAVAILABLE, result.state)
            assertEquals("配置接口", result.stage)
            assertTrue(result.text.contains("HTTP 503"))
            assertEquals(2, server.requestCount)
        }
    }

}
