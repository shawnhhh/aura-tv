package tv.herrin.app

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class M434SpiderPlaybackRecoveryTest {
    private val site = TvBoxSite(name = "自有测试站", needsAdapter = true,
        key = "authorized", spiderApi = "csp_Test",
        headers = mapOf("Referer" to "https://licensed.example/"))

    private fun film(sourceUrl: String): Film {
        val listing = SpiderVodCodec.listing(
            """{"list":[{"vod_id":"test","vod_name":"原创演示视频"}]}""", site).single()
        return SpiderVodCodec.detail(
            """{"list":[{"vod_play_from":"高清","vod_play_url":"正片$$sourceUrl"}]}""",
            listing)
    }

    @Test fun fallbackAcceptsOnlyExactDirectSourceUrlAndPreservesLicensedHeaders() {
        val movie = film("https://media.licensed.example/film.m3u8?sig=opaque")
        val result = SpiderDirectPlayback.resolve(movie, 1, "spider-0", site, false)
        assertNotNull(result)
        assertEquals("https://media.licensed.example/film.m3u8?sig=opaque", result!!.media.uri)
        assertEquals(PlaybackFormatRetry.HLS, result.media.declaredMime)
        assertEquals("https://licensed.example/", result.media.headers["Referer"])
        assertEquals(null, SpiderDirectPlayback.resolve(movie, 1, "other", site, false))
    }

    @Test fun fallbackDoesNotLeakSiteCookieOrAuthorizationToMediaCdn() {
        val withSecrets = site.copy(headers = mapOf(
            "User-Agent" to "HERRIN-Test/1.0",
            "Referer" to "https://licensed.example/",
            "Cookie" to "private-session=never-share",
            "Authorization" to "Bearer never-share"
        ))
        val linked = SpiderVodCodec.detail(
            """{"list":[{"vod_play_from":"高清","vod_play_url":"正片$https://media.licensed.example/movie.mp4"}]}""",
            SpiderVodCodec.listing(
                """{"list":[{"vod_id":"m","vod_name":"测试"}]}""", withSecrets).single())
        val media = SpiderDirectPlayback.resolve(linked, 1, "spider-0", withSecrets, false)!!.media
        assertEquals("HERRIN-Test/1.0", media.headers["User-Agent"])
        assertEquals("https://licensed.example/", media.headers["Referer"])
        assertFalse(media.headers.keys.any { it.equals("Cookie", true) ||
            it.equals("Authorization", true) })
    }

    @Test fun fallbackRejectsOpaqueBrowserScriptWrongSiteAndUnapprovedHttp() {
        val opaque = film("opaque:some-provider-id")
        assertNull(SpiderDirectPlayback.resolve(opaque, 1, "spider-0", site, false))
        val page = film("https://licensed.example/parse.html?id=x")
        assertNull(SpiderDirectPlayback.resolve(page, 1, "spider-0", site, false))
        val script = film("javascript:alert(1)")
        assertNull(SpiderDirectPlayback.resolve(script, 1, "spider-0", site, false))
        val http = film("http://media.licensed.example/film.mp4")
        assertNull(SpiderDirectPlayback.resolve(http, 1, "spider-0", site, false))
        assertNotNull(SpiderDirectPlayback.resolve(http, 1, "spider-0", site, true))
        val unrelated = site.copy(key = "other-source", headers = mapOf("Authorization" to "private"))
        assertNull(SpiderDirectPlayback.resolve(film("https://media.licensed.example/a.mp4"),
            1, "spider-0", unrelated, false))
    }

    @Test fun fallbackOnlyAfterExplicitTransportFailureNeverAfterNormalProviderRejection() {
        assertTrue(SpiderDirectPlayback.eligibleFailure("Spider 子进程异常退出，最后阶段：获取播放地址"))
        assertTrue(SpiderDirectPlayback.eligibleFailure("Spider 服务响应超时，请重新应用站点"))
        assertFalse(SpiderDirectPlayback.eligibleFailure("服务器 403"))
        assertFalse(SpiderDirectPlayback.eligibleFailure("此线路需要网页解析器"))
        assertFalse(SpiderDirectPlayback.eligibleFailure("没有播放地址"))
    }

    @Test fun osExitCategoriesRemainFixedPrivacySafeLabels() {
        assertTrue(SpiderExitDiagnostics.reasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE)
            .contains("原生组件"))
        assertTrue(SpiderExitDiagnostics.reasonLabel(ApplicationExitInfo.REASON_ANR)
            .contains("无响应"))
        assertTrue(SpiderExitDiagnostics.reasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY)
            .contains("内存"))
        assertFalse(SpiderExitDiagnostics.reasonLabel(123456).contains("http"))
    }

    @Test fun timeoutIsDistinctFromActualNativeCrashInCrossProcessBreadcrumb() {
        assertNotEquals(SpiderStartupTrace.Stage.WATCHDOG,
            SpiderStartupTrace.Stage.PLAYER)
        assertTrue(SpiderStartupTrace.Stage.WATCHDOG.display.contains("超时"))
    }
}
