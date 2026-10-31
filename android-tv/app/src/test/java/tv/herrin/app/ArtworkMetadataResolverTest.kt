package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkMetadataResolverTest {
    @get:Rule val temp = TemporaryFolder()
    private val exampleToken = "abcDEF1234567890abcDEF1234567890"
    private fun film(title: String, year: String, category: String = "剧集") = Film(
        "tvbox-example", title, "", category, year, "", R.drawable.photofilm_orbit)

    @Test fun `title cleaning removes bitrate tags while preserving film identity`() {
        assertEquals("庆余年第二季", ArtworkTitle.normalize(" 庆余年 第二季【4K·国语】 "))
        assertEquals("庆余年 第二季", ArtworkTitle.searchQuery("庆余年 第二季【4K】"))
        assertEquals("thelastofus", ArtworkTitle.normalize("The Last of Us [1080p]"))
        assertEquals("庆余年2", ArtworkTitle.normalize("庆余年2"))
        assertFalse(ArtworkTitle.normalize("庆余年2") == ArtworkTitle.normalize("庆余年"))
    }

    @Test fun `strict title and year matching avoids similarly titled remakes and sequels`() {
        val choices = listOf(
            ArtworkSearchCandidate(listOf("城市之光"), 1931, "/old.jpg", "/old-bg.jpg"),
            ArtworkSearchCandidate(listOf("城市之光"), 2026, "/new.jpg", "/new-bg.jpg"),
            ArtworkSearchCandidate(listOf("城市之光2"), 2026, "/sequel.jpg", "/sequel-bg.jpg")
        )
        assertEquals("/new.jpg", ArtworkMatch.best("城市之光", "2026", choices)?.posterPath)
        assertNull(ArtworkMatch.best("城市之光", "", choices))
        assertNull(ArtworkMatch.best("城市之光", "2012", choices))
        assertNull(ArtworkMatch.best("城市之光3", "2026", choices))
    }

    @Test fun `explicit Chinese season ordinals match same sequel but not another`() {
        val choices = listOf(
            ArtworkSearchCandidate(listOf("庆余年 第二季"), 2024, "/season2.jpg", null),
            ArtworkSearchCandidate(listOf("庆余年 第三季"), 2026, "/season3.jpg", null)
        )
        assertEquals("/season2.jpg", ArtworkMatch.best("庆余年2", "2024", choices)?.posterPath)
        assertEquals("/season2.jpg", ArtworkMatch.best("庆余年 第2季", "2024", choices)?.posterPath)
        assertNull(ArtworkMatch.best("庆余年1", "2024", choices))
        assertNull(ArtworkMatch.best("庆余年2", "2020", choices))
        assertNull(ArtworkMatch.best("庆余年4", "", choices))
    }

    @Test fun `batch source season suffix can resolve actual poster without accepting fuzzy matches`() {
        val calls = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            calls += url
            when {
                "/search/tv?" in url -> """{"results":[{"id":24002,"name":"庆余年 第二季",
                    "first_air_date":"2024-05-16","poster_path":"/safe.jpg"}]}"""
                "/tv/24002?" in url -> """{"id":24002,"credits":{"cast":[]}}"""
                else -> error("Unexpected TMDB route")
            }
        })
        val result = resolver.lookup(film("庆余年2", "2024", "电视剧"), exampleToken)
        assertEquals("https://image.tmdb.org/t/p/w780/safe.jpg", result?.posterUrl)
        assertEquals("tv", result?.mediaType)
        assertEquals(2, calls.size)
        assertTrue(calls.first().contains("/search/tv?"))
    }

    @Test fun `token absent or unsafe means no metadata HTTP request`() {
        var attempts = 0
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { _, _ ->
            attempts++
            """{"results":[]}"""
        })
        assertNull(resolver.lookup(film("测试", "2026"), ""))
        assertNull(resolver.lookup(film("测试", "2026"), "Bearer\r\nInjected"))
        assertEquals(0, attempts)
    }

    @Test fun `official search supplies high resolution distinct poster and landscape backed by disk cache`() {
        var attempts = 0
        val endpoint = mutableListOf<String>()
        val cacheDir = temp.newFolder()
        val resolver = ArtworkMetadataResolver(cacheDir, ArtworkJsonFetcher { url, token ->
            attempts++
            endpoint += url
            assertEquals(exampleToken, token)
            """{"results":[{"name":"庆余年 第二季","original_name":"庆余年 第二季",
              "first_air_date":"2024-05-16","poster_path":"/portrait.jpg",
              "backdrop_path":"/wide.jpg","adult":false}]}"""
        })
        val selected = film("庆余年 第二季【4K】", "2024")
        assertEquals("https://image.tmdb.org/t/p/w780/portrait.jpg",
            resolver.lookup(selected, exampleToken)?.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/original/wide.jpg",
            resolver.lookup(selected, exampleToken)?.backdropUrl)
        assertEquals(1, attempts)
        assertTrue(endpoint.single().contains("/search/tv?query="))
        assertTrue(endpoint.single().contains("language=zh-CN"))
        assertTrue(endpoint.single().startsWith("https://api.themoviedb.org/3/"))
        val nextInstance = ArtworkMetadataResolver(cacheDir, ArtworkJsonFetcher { _, _ ->
            throw AssertionError("metadata should be cached")
        })
        assertEquals("https://image.tmdb.org/t/p/original/wide.jpg",
            nextInstance.lookup(selected, exampleToken)?.backdropUrl)
    }

    @Test fun `empty lookup is negative cached to avoid repeated loading delays`() {
        var attempts = 0
        val cacheDir = temp.newFolder()
        val resolver = ArtworkMetadataResolver(cacheDir, ArtworkJsonFetcher { _, _ ->
            attempts++
            """{"results":[]}"""
        })
        val film = film("没有匹配的作品", "2024")
        assertNull(resolver.lookup(film, exampleToken))
        assertNull(resolver.lookup(film, exampleToken))
        assertEquals(2, attempts)
        // Both official movie and TV searches were exhausted exactly once.
        // A fresh resolver likewise must not repeatedly query the exact same empty result.
        assertNull(ArtworkMetadataResolver(cacheDir, ArtworkJsonFetcher { _, _ ->
            throw AssertionError("persistent negative cache was bypassed")
        }).lookup(film, exampleToken))
        // Expiry permits eventual recovery when the upstream TMDB catalog changes.
        cacheDir.listFiles()!!.single().setLastModified(System.currentTimeMillis() - 4L * 3600 * 1000)
        assertNull(resolver.lookup(film, exampleToken))
        assertEquals(4, attempts)
    }

    @Test fun `wrong TVBox category may fall back to exact TV match`() {
        val calls = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            calls += url
            when {
                "/search/movie" in url -> """{"results":[]}"""
                "/search/tv" in url -> """{"results":[{"id":2026,"name":"长夜归来",
                    "original_name":"长夜归来","first_air_date":"2026-02-08",
                    "poster_path":"/tv-poster.jpg","backdrop_path":"/tv-bg.jpg"}]}"""
                "/tv/2026" in url -> """{"id":2026,"credits":{"cast":[]}}"""
                else -> error("Unexpected official TMDB route")
            }
        })
        val found = resolver.lookup(film("长夜归来", "2026", "电影"), exampleToken)
        assertEquals("tv", found?.mediaType)
        assertEquals(2026, found?.tmdbTvId)
        assertEquals("https://image.tmdb.org/t/p/w780/tv-poster.jpg", found?.posterUrl)
        assertTrue(calls.first().contains("/search/movie"))
        assertTrue(calls[1].contains("/search/tv"))
        assertTrue(calls.last().contains("/tv/2026"))
    }

    @Test fun `CMS genre labeled as action film is searched instead of dropped`() {
        assertEquals("movie", ArtworkTitle.identify(film("追光者", "2026", "动作片")).mediaType)
        val requested = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            requested += url
            when {
                "/search/movie" in url -> """{"results":[{"id":437,"title":"追光者",
                    "release_date":"2026-01-01","poster_path":"/movie.jpg"}]}"""
                "/movie/437" in url -> """{"id":437,"credits":{"cast":[]}}"""
                else -> error("Genre-based movie must not need a TV fallback")
            }
        })
        assertEquals("https://image.tmdb.org/t/p/w780/movie.jpg",
            resolver.lookup(film("追光者", "2026", "动作片"), exampleToken)?.posterUrl)
        assertEquals(2, requested.size)
        assertTrue(requested.first().contains("/search/movie"))
    }

    @Test fun `unknown CMS type searches movie and TV without guessing wrong title`() {
        val requested = mutableListOf<String>()
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { url, _ ->
            requested += url
            when {
                "/search/movie" in url -> """{"results":[]}"""
                "/search/tv" in url -> """{"results":[{"id":730,"name":"星河计划",
                    "first_air_date":"2026-06-01","poster_path":"/tv.jpg"}]}"""
                "/tv/730" in url -> """{"id":730,"credits":{"cast":[]}}"""
                else -> error("Unexpected official TMDB route")
            }
        })
        val result = resolver.lookup(film("星河计划", "2026", "未知"), exampleToken)
        assertEquals("tv", result?.mediaType)
        assertEquals(730, result?.tmdbTvId)
        assertEquals(3, requested.size)
        assertTrue(requested[0].contains("/search/movie"))
        assertTrue(requested[1].contains("/search/tv"))
        assertTrue(requested[2].contains("/tv/730"))
    }

    @Test fun `traversal image paths and adult candidates are not accepted`() {
        val resolver = ArtworkMetadataResolver(temp.newFolder(), ArtworkJsonFetcher { _, _ ->
            """{"results":[{"title":"安全影片","original_title":"安全影片",
              "release_date":"2026-01-01","poster_path":"/../secret.jpg",
              "backdrop_path":"//evil.org/track.jpg"},
              {"title":"安全影片","release_date":"2026-01-01",
              "adult":true,"poster_path":"/adult.jpg"}]}"""
        })
        assertNull(resolver.lookup(film("安全影片", "2026", "电影"), exampleToken))
    }
}
