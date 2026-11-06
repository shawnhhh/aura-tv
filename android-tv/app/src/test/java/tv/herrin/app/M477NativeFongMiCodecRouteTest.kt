package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.herrin.app.nativeplayback.NativeFongMiProxy
import tv.herrin.app.nativeplayback.NativeFongMiRoute
import java.io.ByteArrayInputStream

/** Protect against accidentally retaining the former do=jar/csp allowlist in
 * the production direct-media fallback after native FongMi migration. */
class M477NativeFongMiCodecRouteTest {
    @Test fun approvedNativeProxyMayBeDirectMediaDespiteSpiderParseFlag() {
        NativeFongMiProxy("approved", { _ ->
            arrayOf(200, "video/mp4", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }, null).use { proxy ->
            val url = NativeFongMiRoute.url(
                "proxy://do=part&siteKey=approved&episode=1", "approved", proxy)
            assertNotNull(url)
            val result = SpiderVodCodec.player(
                JSONObject().put("parse", 1).put("url", url).toString(),
                allowHttp = true)
            assertTrue("Native approved Spider routes must not be rejected by old do whitelist",
                result is PlaybackResult.Ready)
            assertEquals(url, (result as PlaybackResult.Ready).media.uri)
        }
    }
}
