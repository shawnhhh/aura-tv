package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M477SpiderVodFallbackTest {
    private val first = "https://provider.example.test/unavailable.m3u8"
    private val backup = "https://provider.example.test/working.m3u8"

    @Test fun explicitHeaderFreeBackupLetsVodStartAfterHttp404Or410() {
        val result = SpiderVodCodec.player(
            """{"parse":0,"url":["$first","$backup"]}""", allowHttp = false)
        val media = (result as PlaybackResult.Ready).media
        assertEquals(first, media.uri)
        assertEquals(listOf(backup), media.alternateUris)
        assertTrue("An explicit header-free provider backup should survive 404/410",
            media.safeNotFoundFallback)

        for (status in listOf(404, 410)) {
            val retries = PlaybackFormatRetry.Session(media.uri, media.alternateUris,
                media.declaredMime, allowNotFoundFallback = media.safeNotFoundFallback)
            assertEquals(backup, retries.next(2004, status)?.uri)
            assertNull(retries.next(2004, status))
        }
        for (status in listOf(401, 403, 429)) {
            val denied = PlaybackFormatRetry.Session(media.uri, media.alternateUris,
                media.declaredMime, allowNotFoundFallback = media.safeNotFoundFallback)
            assertNull("HTTP $status must never bypass a provider denial",
                denied.next(2004, status))
        }
    }

    @Test fun authenticationHeadersMustNotEnableAutomaticNotFoundFailover() {
        val result = SpiderVodCodec.player(
            """{"parse":0,"url":["$first","$backup"],
                "header":{"Authorization":"Bearer PRIVATE"}}""", allowHttp = false)
        val media = (result as PlaybackResult.Ready).media
        assertTrue(media.headers.containsKey("Authorization"))
        assertFalse(media.safeNotFoundFallback)
        val retries = PlaybackFormatRetry.Session(media.uri, media.alternateUris,
            media.declaredMime, allowNotFoundFallback = media.safeNotFoundFallback)
        assertNull(retries.next(2004, 404))
    }

    @Test fun spiderIpcPreservesSafeFallbackButCannotForgeItWithAuthOrNoBackup() {
        val media = (SpiderVodCodec.player(
            """{"parse":0,"url":["$first","$backup"]}""", allowHttp = false)
            as PlaybackResult.Ready).media
        val encoded = SpiderIpc.playback(media)
        val decoded = SpiderIpc.readPlayback(encoded, false) as PlaybackResult.Ready
        assertEquals(media, decoded.media)
        assertTrue(decoded.media.safeNotFoundFallback)

        val withAuth = org.json.JSONObject(encoded.toString())
            .put("headers", org.json.JSONObject().put("Authorization", "Bearer PRIVATE"))
        val authenticated = SpiderIpc.readPlayback(withAuth, false) as PlaybackResult.Ready
        assertFalse(authenticated.media.safeNotFoundFallback)

        val withNoBackup = org.json.JSONObject(encoded.toString())
            .put("alternates", org.json.JSONArray())
        val noBackup = SpiderIpc.readPlayback(withNoBackup, false) as PlaybackResult.Ready
        assertFalse(noBackup.media.safeNotFoundFallback)
    }

    @Test fun singleUrlNeverOptedIntoNotFoundFallback() {
        val result = SpiderVodCodec.player(
            """{"parse":0,"url":"$first"}""", allowHttp = false)
        val media = (result as PlaybackResult.Ready).media
        assertTrue(media.alternateUris.isEmpty())
        assertFalse(media.safeNotFoundFallback)
    }
}
