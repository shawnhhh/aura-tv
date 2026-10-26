package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LivePlaylistTest {
    @Test fun readsNamedHttpsIptvWithoutImplicitlyTrustingOtherSchemes() {
        val list = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news" group-title="News, Local",新闻频道
            https://licensed.example.org/channel/news.m3u8
            #EXTINF:-1,不安全频道
            http://insecure.example.org/watch
            #EXTINF:-1 group-title="Sports",体育频道
            https://licensed.example.org/sports.m3u8
            #EXTINF:-1,重复频道
            https://licensed.example.org/channel/news.m3u8
        """.trimIndent()
        val channels = AuthorizedLivePlaylist.decode(list)
        assertEquals(2, channels.size)
        assertEquals("新闻频道", channels[0].name)
        assertEquals("News, Local", channels[0].group)
        assertEquals("体育频道", channels[1].name)
    }

    @Test fun hlsVideoManifestAndNonHttpsChannelsAreNotTreatedAsIptv() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedLivePlaylist.decode("#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nhttps://example.org/part.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedLivePlaylist.decode("#EXTM3U\n#EXTINF:-1,频道\nfile:///storage/stream.m3u8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            val oversized = buildString {
                append("#EXTM3U\n")
                repeat(251) { index ->
                    append("#EXTINF:-1,频道$index\nhttps://safe.example.org/$index\n")
                }
            }
            AuthorizedLivePlaylist.decode(oversized)
        }
    }
}
