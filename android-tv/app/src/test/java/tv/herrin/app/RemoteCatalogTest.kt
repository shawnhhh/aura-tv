package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCatalogTest {
    @Test fun onlyExplicitHttpsUrlsAreAccepted() {
        assertEquals("https://media.example.org/catalog.json",
            AuthorizedUrls.https(" https://media.example.org/catalog.json "))
        assertNull(AuthorizedUrls.https("http://media.example.org/catalog.json"))
        assertNull(AuthorizedUrls.https("file:///sdcard/downloads/file.mp4"))
        assertNull(AuthorizedUrls.https("https://user:secret@media.example.org/file.mp4"))
        assertNull(AuthorizedUrls.https("javascript:alert(1)"))
        assertNull(AuthorizedUrls.https("https://media.example.org/catalog.json#fragment"))
    }

    @Test fun rejectsAmbiguousAuthorityAndAcceptsCdnQuery() {
        listOf("https://example.org:0/file", "https://example.org:65536/file",
            "https://user%40example.org/file", "https://user:pass@example.org/file",
            "https://example.org%2f.evil/file").forEach { assertNull(it, AuthorizedUrls.https(it)) }
        assertEquals("https://cdn.example.org:8443/file.mp4?token=signature",
            AuthorizedUrls.https("https://cdn.example.org:8443/file.mp4?token=signature"))
    }
    @Test fun parsesAuthorizedFilmMetadataAndKeepsFallbackArtwork() {
        val json = """
          {
            "version": 1,
            "items": [
              {
                "id": "authorized-example",
                "title": "授权测试影片",
                "subtitle": "AUTHORIZED DEMO",
                "category": "电影",
                "year": "2026",
                "synopsis": "来自用户明确配置的测试目录。",
                "artwork_url": "https://images.example.org/poster.jpg",
                "episodes": 2
              }
            ]
          }
        """.trimIndent()
        val films = CatalogJson.decode(json)
        assertEquals(1, films.size)
        assertEquals("authorized-example", films[0].id)
        assertEquals("授权测试影片", films[0].title)
        assertEquals("https://images.example.org/poster.jpg", films[0].artworkUrl)
        assertEquals(2, films[0].episodes)
        assertTrue(films[0].artwork != 0)
    }

    @Test fun rejectsDuplicateAndInvalidFilmRecords() {
        val duplicated = """{"version":1,"items":[{"id":"a","title":"甲"},{"id":"a","title":"乙"}]}"""
        assertThrows(IllegalArgumentException::class.java) { CatalogJson.decode(duplicated) }
        val invalid = """{"version":1,"items":[{"id":"bad/id","title":"甲"}]}"""
        assertThrows(IllegalArgumentException::class.java) { CatalogJson.decode(invalid) }
        val noItems = """{"version":1,"items":[]}"""
        assertThrows(IllegalArgumentException::class.java) { CatalogJson.decode(noItems) }
    }

    @Test fun stripsNonHttpsPosterAndClampsEpisodeCount() {
        val payload = """{"version":1,"items":[{"id":"okay","title":"测试","category":"动漫","artwork_url":"http://example.com/poster.jpg","episodes":200}]}"""
        val film = CatalogJson.decode(payload).first()
        assertNull(film.artworkUrl)
        assertEquals(99, film.episodes)
        assertEquals("动漫", film.category)
    }
}
