package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M472ProxyRouteDiagnosticTest {
    @Test fun incorrectSiteAndForgedStaticMarkerHaveSafePreciseCodes() {
        SpiderLoopbackProxy("approved") {
            arrayOf(200, "video/mp4", java.io.ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            val wrong = "http://127.0.0.1:9978/proxy?do=jar&siteKey=other&token=PRIVATE"
            assertEquals("SITE_KEY_MISMATCH",
                SpiderProxyPlayback.routeReason(wrong, "approved", proxy))
            val failed = SpiderProxyPlayback.rebind(
                PlaybackMedia(wrong), "approved", proxy) as PlaybackResult.Unavailable
            val report = PlaybackErrorReport.source("SPIDER", failed.reason)
            assertTrue(report.contains("SOURCE_PROXY_INVALID_ROUTE"))
            assertTrue(report.contains("SITE_KEY_MISMATCH"))
            assertFalse(report.contains("PRIVATE"))
            assertFalse(report.contains("other"))
            assertEquals("STATIC_MARKER_FORGED",
                SpiderProxyPlayback.routeReason(
                    "proxy://do=jar&__herrin_static=1&PRIVATE=SECRET",
                    "approved", proxy))
            assertEquals("INVALID_ALIAS",
                SpiderProxyPlayback.playerResponseReason(
                    """{"url":"proxy:///private-path?token=SECRET"}""", "approved", proxy))
        }
    }

    @Test fun observedCspIsRecognizedAsValidJavaOperationNotAnUnsupportedRoute() {
        SpiderLoopbackProxy("selected") {
            arrayOf(200, "video/mp4",
                java.io.ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            assertEquals("ALIAS_REJECTED",
                SpiderProxyPlayback.routeReason(
                    "proxy://do=csp&siteKey=selected", "selected", proxy))
            assertNotNull(SpiderProxyPlayback.normalizePlayerResponse(
                """{"parse":0,"url":"proxy://do=csp&siteKey=selected&part=one"}""",
                "selected", proxy))
        }
    }

    @Test fun staticJavaOperationIsExplicitButUnrelatedDoAndSecretsStayHidden() {
        SpiderLoopbackProxy("approved") {
            arrayOf(200, "video/mp4", java.io.ByteArrayInputStream(byteArrayOf(1)))
        }.use { proxy ->
            assertEquals("ALIAS_REJECTED",
                SpiderProxyPlayback.playerResponseReason(
                    """{"url":"proxy://do=live&id=sample"}""", "approved", proxy))
            assertEquals("LIVE",
                SpiderProxyPlayback.safeOperation(
                    """{"url":"proxy://do=live&token=SENSITIVE"}""", proxy))
            assertEquals("OTHER",
                SpiderProxyPlayback.safeOperation(
                    """{"url":"proxy://do=USER_PRIVATE_PASSWORD&token=SENSITIVE"}""", proxy))
            val safeReport = PlaybackErrorReport.source("SPIDER",
                "PROXY_INVALID_ROUTE route_reason=UNSUPPORTED_OPERATION route_operation=OTHER " +
                    "token=PRIVATE")
            assertTrue(safeReport.contains("代理操作类型：OTHER"))
            assertFalse(safeReport.contains("PRIVATE"))
        }
    }

    @Test fun malformedPlayerJsonAndInvalidPercentQueriesCannotLeakOrCrash() {
        SpiderLoopbackProxy("approved") {
            arrayOf(200, "video/mp4", java.io.ByteArrayInputStream(byteArrayOf()))
        }.use { proxy ->
            assertEquals("UNSUPPORTED_OPERATION",
                SpiderProxyPlayback.playerResponseReason(
                    """{"url":"proxy://do=wrong&password=DO_NOT_EXPOSE"}""",
                    "approved", proxy))
            assertEquals("INVALID_QUERY",
                SpiderProxyPlayback.routeReason("proxy://do=jar&url=%BAD%",
                    "approved", proxy))
            assertEquals("INVALID_PLAYER_JSON",
                SpiderProxyPlayback.playerResponseReason("password=SECRET",
                    "approved", proxy))
            assertFalse(PlaybackErrorReport.source("SPIDER",
                "PROXY_INVALID_ROUTE route_reason=FORGED_SECRET")
                .contains("FORGED_SECRET"))
        }
    }
}
