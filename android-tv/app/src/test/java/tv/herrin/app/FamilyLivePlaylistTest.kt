package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class FamilyLivePlaylistTest {
    @Test fun importsOnlyLocalUnicastAndKeepsChannelMetadata() {
        val parsed = FamilyLivePlaylist.decode("""
            #EXTM3U
            #EXTINF:-1 tvg-id="cq" group-title="重庆, 本地",重庆新闻
            rtsp://192.168.31.70:554/live/channel1
            #EXTINF:-1,高清体育
            http://192.168.31.72:8080/live/index.m3u8
            #EXTINF:-1,远端合法单播
            https://streams.example.test/live.m3u8
            #EXTINF:-1,组播不兼容
            rtp://239.0.0.1:1234
            #EXTINF:-1,UDP 组播不兼容
            udp://@239.0.0.2:1234
            #EXTINF:-1,重复频道
            rtsp://192.168.31.70:554/live/channel1
        """.trimIndent())
        assertEquals(3, parsed.channels.size)
        assertEquals(3, parsed.skipped)
        assertEquals("重庆新闻", parsed.channels[0].name)
        assertEquals("重庆, 本地", parsed.channels[0].group)
        assertEquals("cq", parsed.channels[0].tvgId)
        assertTrue(parsed.channels[0].url.startsWith("rtsp://"))
    }

    @Test fun rejectsInvalidOrCredentialBearingUrls() {
        listOf(
            "udp://239.0.0.1:1234", "rtp://239.0.0.1:1234", "file:///tmp/movie.mp4",
            "rtsp://user:pass@192.168.1.1/stream", "http://127.0.0.1/#fragment",
            "rtsp://", "http://192.168.1.20:0/live", "http://192.168.1.20:70000/live",
            "http://239.0.0.1/hls.m3u8", "rtsp://[ff02::1]:554/live"
        ).forEach { assertNull(it, FamilyLiveUrls.unicast(it)) }
        assertNotNull(FamilyLiveUrls.unicast("rtsp://[2001:db8::1]:554/live"))
    }

    @Test fun invalidImportIsRejectedBeforeReplacingStoredList() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyLivePlaylist.decode("#EXTM3U\n#EXTINF:-1,频道\nrtp://239.0.0.1:1234")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FamilyLivePlaylist.decode("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nhttp://example.com/seg.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FamilyLivePlaylist.decode("#EXTM3U\n#EXTINF:-1,频道\nhttp://example.com/part.m3u8".repeat(10_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FamilyLivePlaylist.decode("#EXTINF:-1,频道\nrtsp://localhost/live")
        }
    }

    @Test fun appliesChannelLimitWithoutTruncatingOrAcceptingPartialList() {
        val playlist = buildString {
            append("#EXTM3U\n")
            repeat(FamilyLivePlaylist.MAX_CHANNELS + 1) { i ->
                append("#EXTINF:-1,频道$i\nrtsp://192.168.1.1/live/$i\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { FamilyLivePlaylist.decode(playlist) }
    }

    @Test fun legacyHttpsOnlyPlaylistRemainsStrict() {
        val legacy = "#EXTM3U\n#EXTINF:-1,家庭 RTSP\nrtsp://192.168.1.50/live"
        assertThrows(IllegalArgumentException::class.java) { AuthorizedLivePlaylist.decode(legacy) }
    }
}
