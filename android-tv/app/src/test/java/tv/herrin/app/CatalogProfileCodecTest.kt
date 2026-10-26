package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogProfileCodecTest {
    @Test fun roundTripChineseNamesAndCanonicalHttpsUrls() {
        val profiles = listOf(
            CatalogProfile("first", "我的片库", "https://media.example.org/catalog.json"),
            CatalogProfile("second", "家庭影视", "https://family.example.org/feed?version=1")
        )
        assertEquals(profiles, CatalogProfileCodec.decode(CatalogProfileCodec.encode(profiles)))
    }

    @Test fun rejectsDuplicateUrlsIdsAndUnsafePayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogProfileCodec.decode("""[{"id":"a","name":"A","url":"http://evil.example/feed"}]""")
        }
        val profiles = listOf(
            CatalogProfile("a", "A", "https://one.example/feed"),
            CatalogProfile("b", "B", "https://one.example/feed")
        )
        assertThrows(IllegalArgumentException::class.java) {
            CatalogProfileCodec.decode(CatalogProfileCodec.encode(profiles))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogProfileCodec.decode("""[{"id":"a","name":"Bad\nName","url":"https://one.example/feed"}]""")
        }
    }

    @Test fun limitsProfileCount() {
        val tooMany = (1..11).map {
            CatalogProfile("id$it", "目录$it", "https://example.org/catalog/$it")
        }
        assertThrows(IllegalArgumentException::class.java) { CatalogProfileCodec.encode(tooMany) }
    }
}
