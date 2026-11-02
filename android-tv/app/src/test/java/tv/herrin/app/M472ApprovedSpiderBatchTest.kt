package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M472ApprovedSpiderBatchTest {
    private val request = HerrinFilmIdentity(1234, "movie", listOf("Sample Film"), 2025)
    private fun site(i: Int) = TvBoxSite("node-$i", needsAdapter = true,
        key = "node-$i", spiderApi = "csp_ApprovedFixture")
    private val fakePoster = Film("spider-fixture", "Sample Film", "", "电影",
        "2025", "", R.drawable.photofilm_orbit)
    private fun candidate(site: TvBoxSite, claim: Int) = HerrinSourceMovie(
        "spider:" + site.key, HerrinSourceType.TVBOX_SPIDER,
        fakePoster, "movie", resolutionClaim = claim)
    private fun playable() = fakePoster.copy(playbackEpisodes = listOf(
        PlaybackEpisode(1, "正片", listOf(PlaybackLine("line", "1080p",
            "https://authorized.example/video.mp4")))))

    @Test fun findsBestPlayableAcrossSitesIgnoringBrokenProxyAndUnapprovedJars() = runBlocking {
        val calls = mutableListOf<String>()
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                calls += "activate:" + site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, when(site.key) {
                    "node-1" -> 2160
                    "node-2" -> 1080
                    else -> 720
                }))
            override suspend fun details(candidate: HerrinSourceMovie) = playable()
            override suspend fun canPlay(detailed: Film) = calls.count { it.startsWith("activate:") } > 1
        }
        val result = HerrinApprovedSpiderBatch.scan(request,
            listOf(site(1), site(2), site(3)), { it.key != "node-3" }, session)
        assertEquals(2, result.eligible)
        assertEquals("node-2", result.match?.site?.key)
        assertEquals(1080, result.match?.claim)
        assertEquals("activate:node-2", calls.last())
    }

    @Test fun neverLaunchesUnapprovedSiteAndPaginationDoesNotLoopFirstNodes() = runBlocking {
        val calls = mutableListOf<String>()
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                calls += site.key; return false
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                emptyList<HerrinSourceMovie>()
            override suspend fun details(candidate: HerrinSourceMovie): Film? = null
            override suspend fun canPlay(detailed: Film) = false
        }
        val all = (1..9).map(::site)
        val first = HerrinApprovedSpiderBatch.scan(request, all, { true }, session)
        assertEquals(6, first.checked)
        assertEquals(6, first.nextOffset)
        val second = HerrinApprovedSpiderBatch.scan(request, all, { true }, session,
            offset = first.nextOffset)
        assertEquals(3, second.checked)
        assertEquals(0, second.nextOffset)
        assertEquals((1..9).map { "node-$it" }, calls)
        assertNull(first.match)
        assertNull(second.match)
    }
}
