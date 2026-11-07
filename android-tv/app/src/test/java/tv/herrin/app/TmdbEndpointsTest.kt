package tv.herrin.app
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class TmdbEndpointsTest {
    @Test fun failoverCooldownAndRecovery() {
        var now = 100L
        val router = TmdbEndpointRouter { now }
        val calls = mutableListOf<String>()
        val request: (String) -> String? = {
            calls += it
            if (it.startsWith(TmdbEndpoints.PRIMARY)) throw UnknownHostException()
            "ok"
        }
        val url = TmdbEndpoints.PRIMARY + "/movie/550?language=zh-CN"
        assertEquals("ok", router.load(url, request))
        assertEquals(listOf(url, TmdbEndpoints.FALLBACK + "/movie/550?language=zh-CN"), calls)
        calls.clear(); router.load(url, request); assertEquals(1, calls.size)
        now += 60_001; calls.clear(); router.load(url, request); assertEquals(url, calls.first())
    }
    @Test fun authAndRateLimitsNeverFailOver() {
        for (failure in listOf(ArtworkAuthenticationFailure(401), ArtworkAuthenticationFailure(403),
            ArtworkRateLimitFailure(), ArtworkHttpStatusFailure(429), ArtworkHttpStatusFailure(404))) {
            var calls = 0
            try { TmdbEndpointRouter().load(TmdbEndpoints.PRIMARY + "/configuration") { calls++; throw failure }; fail() }
            catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }
    @Test fun safeServerFailuresFailOver() {
        for (status in listOf(500, 502, 503, 504)) {
            var calls = 0
            assertEquals("ok", TmdbEndpointRouter().load(TmdbEndpoints.PRIMARY + "/tv/1/credits") {
                if (++calls == 1) throw ArtworkHttpStatusFailure(status) else "ok"
            })
            assertEquals(2, calls)
        }
    }
}
