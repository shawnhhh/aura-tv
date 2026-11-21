package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalNfoIdentityTest {
    @Test fun onlyTypedTmdbIdsAreUsedAndExternalEntitiesAreNeverExpanded() {
        assertEquals(PersonalNfoIdentity.Hint("movie",42), PersonalNfoIdentity.parse("<movie><uniqueid type='tmdb' default='true'>42</uniqueid></movie>"))
        assertEquals(PersonalNfoIdentity.Hint("tv",12), PersonalNfoIdentity.parse("<tvshow><tmdbid>12</tmdbid></tvshow>"))
        assertNull(PersonalNfoIdentity.parse("<movie><uniqueid type='imdb'>42</uniqueid></movie>"))
        assertNull(PersonalNfoIdentity.parse("<!DOCTYPE x SYSTEM 'https://example.invalid'><movie><tmdbid>42</tmdbid></movie>"))
        assertNull(PersonalNfoIdentity.parse("<movie><tmdbid>0</tmdbid></movie>"))
    }
}
