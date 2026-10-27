package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TvBoxWrappedConfigTest {
    private fun wrappedJson(json: String): ByteArray {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22,
            0xFF.toByte(), 0xD9.toByte())
        val trailer = ("DYxAlYOt**" + Base64.getEncoder().encodeToString(json.toByteArray()))
            .toByteArray(Charsets.US_ASCII)
        return jpeg + trailer
    }

    @Test fun extractsImageWrappedConfigWithObservedMarkerAndCommentSyntax() {
        val json = """{
          "spider":"https://example.org/adapter.jpg;md5;1234",
          // This legacy entry is intentionally disabled.
          "sites":[{"name":"兼容测试","api":"csp_Example","type":3}],
          "lives":[{"name":"直播","url":"https://example.org/list.m3u"}]
        }"""
        val extracted = TvBoxWrappedConfig.extract(wrappedJson(json))
        assertNotNull(extracted)
        val config = TvBoxConfigParser.parse(extracted!!, "http://www.饭太硬.net/tv") as TvBoxConfig.Single
        assertEquals(1, config.sites.size)
        assertEquals(1, config.lives.size)
        assertTrue(config.hasExternalEngine)
        assertEquals("https://example.org/list.m3u", config.lives.single().sourceUrl)
    }

    @Test fun preservesAllSitesAndLiveEntriesInFortyNineSiteSingleWarehouse() {
        // Synthetic scale fixture matching the observed structure, not a bundled provider config.
        val json = buildString {
            append("""{"spider":"https://example.org/adapter.jar","sites":[""")
            for (index in 0..48) {
                if (index != 0) append(',')
                val api = if (index < 3) "https://example.org/cms$index" else "csp_Test$index"
                append("""{"name":"演示站点$index","type":3,"api":"$api"}""")
            }
            append("""],"lives":[""")
            for (index in 0..5) {
                if (index != 0) append(',')
                append("""{"name":"演示直播$index","url":"https://example.org/live$index.m3u"}""")
            }
            append("]}")
        }
        val decoded = TvBoxWrappedConfig.extract(wrappedJson(json))
        assertNotNull(decoded)
        val parsed = TvBoxConfigParser.parse(decoded!!, "http://www.饭太硬.net/tv") as TvBoxConfig.Single
        assertEquals(49, parsed.sites.size)
        assertEquals(3, parsed.sites.count { it.apiUrl != null })
        assertEquals(46, parsed.sites.count { it.needsAdapter })
        assertEquals("演示站点48", parsed.sites.last().name)
        assertEquals(6, parsed.lives.size)
        assertTrue(parsed.hasExternalEngine)
    }

    @Test fun stripsCommentsButPreservesSlashesWithinQuotedUrls() {
        val json = """{"sites":[/* a site */{"name":"A","api":"https://api.example.org/test//v1"}],
          // removed
          "lives":[] // trailing comment
          }"""
        assertEquals("https://api.example.org/test//v1",
            (TvBoxConfigParser.parse(json, "https://example.org") as TvBoxConfig.Single).sites.single().apiUrl)
    }

    @Test fun rejectsOrdinaryImageInvalidEncodingAndUnsupportedSuffix() {
        val plainJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0xFF.toByte(), 0xD9.toByte())
        assertNull(TvBoxWrappedConfig.extract(plainJpeg))
        assertNull(TvBoxWrappedConfig.extract(plainJpeg + "random**%%%notbase64".toByteArray()))
        assertNull(TvBoxWrappedConfig.extract(plainJpeg + "https://somewhere.invalid".toByteArray()))
        assertNull(TvBoxWrappedConfig.extract(wrappedJson("[]")))
    }
}
