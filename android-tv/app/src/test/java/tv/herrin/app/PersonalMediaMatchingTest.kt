package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalMediaMatchingTest {
    @Test fun originalFilenameMatchesLocalizedTmdbMetadataButAmbiguousAndWrongIdentitiesStayUnmatched() {
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> null })
        val localized = discovery.parsePage("""{"results":[{"id":157336,"title":"星际穿越","original_title":"Interstellar","release_date":"2014-11-05"}]}""", "movie").single()
        val expected = HerrinFilmIdentity(1, "movie", listOf("Interstellar"), 2014)
        assertEquals(localized, PersonalMediaMatching.choose(expected, listOf(localized), discovery))
        assertNull(PersonalMediaMatching.choose(expected, listOf(localized.copy(year = "1999")), discovery))
        assertNull(PersonalMediaMatching.choose(expected, listOf(localized.copy(id = "tmdb-tv-157336")), discovery))
        assertNull(PersonalMediaMatching.choose(expected, listOf(localized, localized.copy(id = "tmdb-movie-42")), discovery))
    }

    @Test fun automaticFilesWithoutEpisodeEvidenceMayBeTvButNeverGuessAcrossAmbiguousTypes() {
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> null })
        val tv = discovery.parsePage("""{"results":[{"id":55,"name":"共同的名字","original_name":"Shared Title","first_air_date":"2020-04-10"}]}""", "tv").single()
        val movie = discovery.parsePage("""{"results":[{"id":66,"title":"共同的名字","original_title":"Shared Title","release_date":"2020-04-10"}]}""", "movie").single()
        val unknown = HerrinFilmIdentity(1, "movie", listOf("Shared Title"), 2020)
        assertEquals(tv, PersonalMediaMatching.chooseUnclassified(unknown, listOf(tv), discovery))
        assertEquals(movie, PersonalMediaMatching.chooseUnclassified(unknown, listOf(movie), discovery))
        assertNull(PersonalMediaMatching.chooseUnclassified(unknown, listOf(movie, tv), discovery))
        assertNull(PersonalMediaMatching.chooseUnclassified(unknown.copy(year = 2022), listOf(tv), discovery))
        assertNull(PersonalMediaMatching.chooseUnclassified(unknown, listOf(tv, tv.copy(id = "tmdb-tv-77")), discovery))
    }
}
