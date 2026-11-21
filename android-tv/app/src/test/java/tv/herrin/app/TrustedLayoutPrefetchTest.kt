package tv.herrin.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class TrustedLayoutPrefetchTest {
    @Test fun onlyConfirmedIdentityPrefetchesAndUnknownSuffixesNeverGainAuthority() = runBlocking {
        val identity = HerrinFilmIdentity(9001, "movie", listOf("协议测试1"), 2025)
        fun movie(id: String, title: String, year: String) = HerrinSourceMovie("fixture", HerrinSourceType.TVBOX_CMS,
            Film(id, title, "", "电影", year, "", 1, cmsVodId = id), "movie")
        val exact = movie("exact", "协议测试1", "2025")
        val wrongYear = movie("wrong-year", "协议测试1", "2020")
        val arbitrarySuffix = movie("suffix", "协议测试1（a）", "2025")
        assertEquals(SourceRelevance.CONFIRMED, HerrinSourceRelevance.classify(identity, exact))
        assertEquals(SourceRelevance.POSSIBLE, HerrinSourceRelevance.classify(identity, wrongYear))
        assertEquals(SourceRelevance.POSSIBLE, HerrinSourceRelevance.classify(identity, arbitrarySuffix))
        val reads = mutableListOf<String>()
        val node = HerrinSourceDiscovery.Node("fixture", "CMS", HerrinSourceType.TVBOX_CMS,
            { listOf(exact, wrongYear, arbitrarySuffix) }, {
                reads += it.film.cmsVodId!!
                HerrinSourceDiscovery.Detail(it.film.copy(playbackEpisodes = listOf(PlaybackEpisode(1, "正片",
                    listOf(PlaybackLine("one", "original", "https://example.invalid/media"))))))
            }, identity = identity)
        var snapshot = HerrinSourceDiscovery.Snapshot("", 0)
        HerrinSourceDiscovery().search("r", listOf(node), null, Mutex()) { snapshot = it }
        assertEquals(listOf("exact"), reads)
        assertEquals(3, snapshot.hits.size)
        assertEquals(1, snapshot.routes.size)
    }
}
