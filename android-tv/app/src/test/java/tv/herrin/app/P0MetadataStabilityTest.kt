package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class P0MetadataStabilityTest {
    private fun film(id: String, name: String) = Film(id,name,"","剧集","2026","",0,tmdbTvId=99,
        metadataSource="TMDB",cmsVodId=id,playbackEpisodes=listOf(PlaybackEpisode(1,"1",listOf(PlaybackLine("a","A","https://media.invalid/$id.mp4")))))
    @Test fun successfulSeriesMatchingCannotDuplicateRecentLazyKeysOrLoseSourceEpisodes() {
        for (count in listOf(20,100,250)) {
            val source = (1..count).map { film("source-$it", "原创剧 第${it}季") }
            val groups = ShowCatalog.build(source,"fixture")
            // Reproduces the old contract violation: raw IDs differ while UI group keys repeat.
            val oldKeys = source.map { groups.groupFor(it.id)?.key ?: it.id }
            assertTrue(oldKeys.toSet().size < oldKeys.size)
            val rail = groups.recentRail(source,source.reversed())
            val keys = rail.map { groups.groupFor(it.id)?.key ?: it.id }
            assertEquals(keys.size,keys.toSet().size)
            assertEquals(source, source.map { current -> groups.groupFor(current.id)?.memberFor(current.id)?.film ?: current })
        }
    }
    @Test fun malformedDetailPreservesTheCurrentFilmAndPlayback() = runBlocking {
        val current = film("tmdb-tv-99","Original")
        val loader = HerrinTmdbDiscovery(ArtworkJsonFetcher { _,_ -> "{broken" })
        assertEquals(current,loader.detail(current,"fixture"))
    }
    @Test fun statusFailureAfterSuccessfulProbeIsRecoverableWithoutUncaughtPageException() = runBlocking {
        for (failure in listOf(ArtworkAuthenticationFailure(401),ArtworkRateLimitFailure())) {
            val loader=HerrinTmdbDiscovery(ArtworkJsonFetcher { _,_ -> throw failure })
            val shelf=loader.load(loader.sections.first(),"fixture")
            assertTrue(shelf.films.isEmpty())
            assertFalse(shelf.isComplete)
        }
    }
    @Test fun crashSummaryOmitsMessagesFileNamesAndSecretsAndBoundsCauseCycles() {
        val secret="privateToken-and-password"
        val error=IllegalArgumentException("https://private.invalid/?token=$secret")
        error.stackTrace=arrayOf(StackTraceElement("tv.herrin.app.MainActivity","render","https://$secret",42))
        val text=SafeCrashSummary.format(error)
        assertTrue(text.contains("MainActivity.render:42"))
        assertFalse(text.contains(secret));assertFalse(text.contains("https://"))
        assertTrue(text.length<=10000)
    }
}
