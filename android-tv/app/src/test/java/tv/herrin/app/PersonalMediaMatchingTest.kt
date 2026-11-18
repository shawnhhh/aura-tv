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
}
