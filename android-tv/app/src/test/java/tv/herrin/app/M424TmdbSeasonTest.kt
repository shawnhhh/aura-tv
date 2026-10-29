package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class M424TmdbSeasonTest {
    private val valid = """{"season_number":3,"episodes":[
      {"season_number":3,"episode_number":5,"name":"启程","still_path":"/episode5.jpg","runtime":24},
      {"season_number":3,"episode_number":6,"name":"未知","still_path":"/../unsafe.jpg","runtime":0}
    ]}"""

    @Test fun acceptsOnlyOfficialSeasonAndValidatedCdNSpecificPath() {
        val parsed = TmdbSeasonParser.parse(valid, 3)
        assertEquals("https://image.tmdb.org/t/p/w780/episode5.jpg", parsed[5]?.stillUrl)
        assertEquals(24, parsed[5]?.runtimeMinutes)
        assertEquals("启程", parsed[5]?.officialTitle)
        assertNull(parsed[6]?.stillUrl)
        assertNull(parsed[6]?.runtimeMinutes)
        assertTrue(TmdbSeasonParser.parse(valid, 2).isEmpty())
        assertTrue(TmdbSeasonParser.parse("not-json", 3).isEmpty())
    }

    @Test fun apiLookupRequiresVerifiedTvIdAndSeasonAndPreservesDisplayOnly() {
        val requests = mutableListOf<String>()
        val cache = File(System.getProperty("java.io.tmpdir"), "herrin-m424-${System.nanoTime()}")
        val resolver = TmdbSeasonEpisodes(cache) { url, _ -> requests += url; valid }
        val token = "a".repeat(48)
        assertTrue(resolver.lookup(null, 3, token).isEmpty())
        assertTrue(resolver.lookup(123, 0, token).isEmpty())
        assertTrue(resolver.lookup(123, 3, "bad-token").isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals(24, resolver.lookup(123, 3, token)[5]?.runtimeMinutes)
        assertEquals(listOf("https://api.themoviedb.org/3/tv/123/season/3?language=zh-CN"), requests)
        assertEquals(24, resolver.lookup(123, 3, token)[5]?.runtimeMinutes)
        assertEquals(1, requests.size) // Second call comes from the local bounded cache.
        cache.listFiles()?.forEach { it.delete() }
        cache.delete()
    }

    @Test fun duplicateNumbersOrWrongSeasonAreRejected() {
        val duplicate = """{"season_number":2,"episodes":[
            {"episode_number":1},{"episode_number":1}]}"""
        assertTrue(TmdbSeasonParser.parse(duplicate, 2).isEmpty())
    }
}
