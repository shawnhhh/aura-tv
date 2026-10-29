package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

/** M4.26: source format must survive Spider -> Binder -> Media3 without guessing URL extensions. */
class M426DeclaredSourceFormatTest {
    @Test fun `extensionless HLS manifest preserves source format and request headers across IPC`() {
        val source = """{"parse":0,"url":"https://video.example.test/tokenized?id=123",
            "format":"application/vnd.apple.mpegurl",
            "header":{"Referer":"https://video.example.test/"}}"""
        val result = SpiderVodCodec.player(source, allowHttp = false)
        assertTrue(result is PlaybackResult.Ready)
        val ready = (result as PlaybackResult.Ready).media
        assertEquals(PlaybackFormatRetry.HLS, ready.declaredMime)
        assertEquals("https://video.example.test/", ready.headers["Referer"])

        val encoded = SpiderIpc.playback(ready)
        val restored = SpiderIpc.readPlayback(encoded, allowHttp = false)
        assertTrue(restored is PlaybackResult.Ready)
        val media = (restored as PlaybackResult.Ready).media
        assertEquals(ready.uri, media.uri)
        assertEquals(PlaybackFormatRetry.HLS, media.declaredMime)
        assertEquals(ready.headers, media.headers)
    }

    @Test fun `DASH declaration is kept but webpage parse request still fails closed`() {
        val direct = SpiderVodCodec.player(
            """{"parse":0,"url":"https://video.example.test/manifest?sig=abc","format":"application/dash+xml"}""",
            allowHttp = false)
        assertEquals(PlaybackFormatRetry.DASH, (direct as PlaybackResult.Ready).media.declaredMime)

        val webParse = SpiderVodCodec.player(
            """{"parse":1,"url":"https://video.example.test/player?id=2","format":"application/vnd.apple.mpegurl"}""",
            allowHttp = false)
        assertTrue(webParse is PlaybackResult.Unavailable)
    }

    @Test fun `HTML or arbitrary format cannot force Media3 to parse a web page as video`() {
        val raw = SpiderVodCodec.player(
            """{"parse":0,"url":"https://video.example.test/watch?id=2","format":"text/html"}""",
            allowHttp = false) as PlaybackResult.Ready
        assertNull(raw.media.declaredMime)
        val roundTrip = SpiderIpc.readPlayback(SpiderIpc.playback(raw.media), allowHttp = false)
            as PlaybackResult.Ready
        assertNull(roundTrip.media.declaredMime)
    }
}
