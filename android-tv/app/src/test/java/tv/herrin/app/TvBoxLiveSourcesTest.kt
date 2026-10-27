package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvBoxLiveSourcesTest {
    @Test fun decodesInlineTvBoxChannelsWithoutRunningAdapters() {
        val config = JSONObject("""{
          "name":"家庭 IPTV", "channels":[
            {"name":"CCTV-1","urls":["http://192.168.1.2/live/1.m3u8",{"url":"https://example.org/1.m3u8"}]},
            {"name":"CCTV-2","url":"https://example.org/2.m3u8","tvg-id":"cctv2"},
            {"name":"非法","urls":["file:///private/key"]}
          ]
        }""")
        val httpsOnly = TvBoxLiveCodec.inlineChannels(config, false)
        assertEquals(2, httpsOnly.size)
        assertEquals("CCTV-1", httpsOnly.first().name)
        assertEquals("家庭 IPTV", httpsOnly.first().group)
        val httpAllowed = TvBoxLiveCodec.inlineChannels(config, true)
        assertEquals(3, httpAllowed.size)
        assertEquals("http://192.168.1.2/live/1.m3u8", httpAllowed.first().url)
        assertEquals("cctv2", httpAllowed.last().tvgId)
    }

    @Test fun decodesTvBoxJsonLives() {
        val channels = TvBoxLiveCodec.decode("""{
          "lives":[{"name":"分组A","channels":[{"name":"教育频道","urls":["https://example.org/edu.m3u8"]}]}]
        }""", false)
        assertEquals(1, channels.size)
        assertEquals("教育频道", channels.single().name)
    }

    @Test fun m3uParsesGroupNameAndRejectsHttpUntilApproved() {
        val body = """#EXTM3U
            |#EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
            |http://192.168.31.2/cctv1
            |#EXTINF:-1 group-title="公共频道",测试公共台
            |https://example.org/stream.m3u8
            |""".trimMargin()
        val httpsOnly = TvBoxLiveCodec.decode(body, false)
        assertEquals(1, httpsOnly.size)
        assertEquals("测试公共台", httpsOnly.single().name)
        val both = TvBoxLiveCodec.decode(body, true)
        assertEquals(2, both.size)
        assertEquals("央视", both.first().group)
        assertEquals("cctv1", both.first().tvgId)
    }

    @Test fun txtRecognizesTvBoxGenreSections() {
        val channels = TvBoxLiveCodec.decode("""央视,#genre#
            CCTV1,https://example.org/one
            地方,#genre#
            重庆卫视,https://example.org/two
            """.trimIndent(), false)
        assertEquals(2, channels.size)
        assertEquals("央视", channels[0].group)
        assertEquals("地方", channels[1].group)
    }

    @Test fun rejectsHlsManifestsAndFilesWithNoUsableChannels() {
        for (payload in listOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\nsegment0.ts",
            "垃圾文件,ftp://evil.example.org/media",
            "{\"lives\":[{\"channels\":[{\"name\":\"A\",\"urls\":[\"file:///secret\"]}]}]}"
        )) {
            assertThrows(IllegalArgumentException::class.java) { TvBoxLiveCodec.decode(payload, true) }
        }
    }
}
