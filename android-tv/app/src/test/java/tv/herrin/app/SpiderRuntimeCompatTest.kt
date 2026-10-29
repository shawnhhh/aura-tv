package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class SpiderRuntimeCompatTest {
    private val site = TvBoxSite("测试站点", true, key = "same", spiderApi = "csp_Demo",
        extension = "original", headers = mapOf("Authorization" to "old-secret"))

    @Test fun sourceEditsInvalidateTheEntireCookieAndSpiderSession() {
        val key = SpiderRuntimeCompat.sessionKey("jar", site)
        assertEquals(64, key.length)
        assertFalse(key.contains("old-secret"))
        assertNotEquals(key, SpiderRuntimeCompat.sessionKey("jar", site.copy(extension = "updated")))
        assertNotEquals(key, SpiderRuntimeCompat.sessionKey("jar", site.copy(spiderApi = "csp_Other")))
        assertNotEquals(key, SpiderRuntimeCompat.sessionKey("jar", site.copy(headers = mapOf("Authorization" to "new-secret"))))
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(SpiderRuntimeCompat.sessionKey("Aa", site), SpiderRuntimeCompat.sessionKey("BB", site))
    }

    @Test fun nativeSelectionUsesTheProcessBitnessAndNeverFeedsArmToX86() {
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        assertEquals("armeabi-v7a", SpiderRuntimeCompat.selectAbi(abis, false))
        assertEquals("arm64-v8a", SpiderRuntimeCompat.selectAbi(abis, true))
        assertNull(SpiderRuntimeCompat.selectAbi(listOf("arm64-v8a"), false))
        assertEquals(listOf("lib/x86_64/libdemo.so"), SpiderRuntimeCompat.nativeEntries(listOf(
            "lib/x86_64/libdemo.so", "lib/arm64-v8a/libdemo.so", "lib/x86_64/../libbad.so",
            "lib/x86_64/sub/libbad.so", "lib/x86_64/not-library"), "x86_64"))
    }

    @Test fun optionalInitializerCannotSwallowWrappedFatalRuntimeErrors() {
        val fatal = OutOfMemoryError("synthetic")
        assertSame(fatal, SpiderInitPolicy.blocking(InvocationTargetException(fatal)))
    }

    @Test fun cleanupContinuesAfterPluginAndLinkageFailures() {
        val completed = mutableListOf<Int>()
        SpiderRuntimeCompat.cleanup(listOf({ throw IllegalStateException("destroy") },
            { completed.add(2); Unit }, { throw UnsatisfiedLinkError("native") },
            { completed.add(4); Unit }))
        assertEquals(listOf(2, 4), completed)
    }

    @Test fun wrappedFailuresDoNotRevealRequestUrlsOrAuthorization() {
        val secret = "private-value"
        val message = SpiderFailureDetails.message(InvocationTargetException(IllegalStateException(
            "failed https://example.org/private?token=$secret Authorization: Bearer $secret")))
        assertTrue(message.startsWith("IllegalStateException"))
        assertFalse(message.contains(secret))
        assertFalse(message.contains("https://"))
        val cookie = SpiderRuntimeCompat.failure(IllegalStateException("Cookie: session=$secret"))
        assertFalse(cookie.contains(secret))
    }

    @Test fun ipcRoundTripPreservesOpaqueMediaIdentifiersAndSessionPermission() {
        val film = Film("id", "title", "sub", "Series", "2026", "synopsis", R.drawable.photofilm_orbit,
            spiderVodId = "vod?opaque=one", playbackEpisodes = listOf(
                PlaybackEpisode(120, "第 120 集", listOf(PlaybackLine("spider-2", "线路二", "id?a=1&b=2")))))
        val restored = SpiderIpc.readFilm(SpiderIpc.film(film))
        assertEquals(film.spiderVodId, restored.spiderVodId)
        assertEquals(film.playbackEpisodes, restored.playbackEpisodes)
        val media = PlaybackMedia("https://example.org/main", headers = mapOf("Cookie" to "session=value"),
            alternateUris = listOf("https://example.org/backup"), declaredMime = "application/x-mpegURL")
        val output = (SpiderIpc.readPlayback(SpiderIpc.playback(media), false) as PlaybackResult.Ready).media
        assertEquals(media.uri, output.uri)
        assertEquals(media.headers, output.headers)
        assertEquals(media.alternateUris, output.alternateUris)
    }
}
