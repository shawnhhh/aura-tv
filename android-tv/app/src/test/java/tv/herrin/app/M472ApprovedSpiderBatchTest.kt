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

    @Test fun winnerIsVerifiedAgainAfterSwitchingFromAnotherSpiderSession() = runBlocking {
        val activated = mutableListOf<String>()
        val checked = mutableListOf<String>()
        var current = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                current = site.key
                activated += site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, if (site.key == "node-1") 2160 else 1080))
            override suspend fun details(candidate: HerrinSourceMovie): Film? {
                assertEquals(candidate.sourceId.substringAfter("spider:"), current)
                return playable()
            }
            override suspend fun canPlay(detailed: Film): Boolean {
                checked += current
                return true
            }
        }
        val result = HerrinApprovedSpiderBatch.scan(request, listOf(site(1), site(2)),
            { true }, session)
        assertEquals("node-1", result.match?.site?.key)
        assertEquals(listOf("node-1", "node-2", "node-1"), activated)
        assertEquals(listOf("node-1", "node-2", "node-1"), checked)
        assertEquals("node-1", current)
    }

    @Test fun expiredTopQualitySessionFallsThroughToRestorableSecondSite() = runBlocking {
        val attempted = mutableListOf<String>()
        val activations = mutableMapOf<String, Int>()
        var current = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                attempted += site.key
                val count = (activations[site.key] ?: 0) + 1
                activations[site.key] = count
                if (site.key == "node-1" && count == 2) return false
                current = site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, if (site.key == "node-1") 2160 else 1080))
            override suspend fun details(candidate: HerrinSourceMovie): Film? = playable()
            override suspend fun canPlay(detailed: Film) = current != ""
        }
        val result = HerrinApprovedSpiderBatch.scan(request,
            listOf(site(1), site(2)), { true }, session)
        assertEquals("node-2", result.match?.site?.key)
        assertEquals(1080, result.match?.claim)
        assertEquals("node-2", current)
        assertEquals(listOf("node-1", "node-2", "node-1", "node-2"), attempted)
    }

    @Test fun batchResultKeepsActuallyValidatedAlternateLineNotTheBrokenFirstLine() = runBlocking {
        val v = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite) = true
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, 2160))
            override suspend fun details(candidate: HerrinSourceMovie) = fakePoster.copy(
                playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(
                    PlaybackLine("broken", "4K", "https://example.test/bad"),
                    PlaybackLine("working", "1080p", "https://example.test/real.mp4")))))
            override suspend fun canPlay(detailed: Film) = error(
                "Production pick() must not rely on first line")
            override suspend fun pick(detailed: Film): Pair<Int,String>? = 1 to "working"
        }
        val result = HerrinApprovedSpiderBatch.scan(request, listOf(site(1)),
            { true }, v)
        assertEquals("working", result.match?.line)
        assertEquals(1, result.match?.episode)
    }

    @Test fun oneTapAutomaticallyChecksNextPagesAndSelectsLaterVerified4kSite() = runBlocking {
        val activated = mutableListOf<String>()
        var active = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                active = site.key
                activated += site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, if (site.key == "node-9") 2160 else 720))
            override suspend fun details(candidate: HerrinSourceMovie): Film? {
                assertEquals(candidate.sourceId.substringAfter("spider:"), active)
                return playable()
            }
            override suspend fun canPlay(detailed: Film): Boolean = true
        }
        val all = (1..15).map(::site)
        val result = HerrinApprovedSpiderBatch.scanAcrossPages(
            request, all, { true }, session, maxWallClockMs = 30_000)
        assertEquals("node-9", result.match?.site?.key)
        assertEquals(2160, result.match?.claim)
        assertEquals(12, result.checked)
        assertEquals(15, result.eligible)
        assertEquals("node-9", active)
        assertTrue("A user should never need to activate node 9 manually",
            activated.contains("node-9"))
        assertEquals(12, (1..12).count { "node-$it" in activated })
    }

    @Test fun allDeadSitesAreScannedWithoutManualSourceSelection() = runBlocking {
        val attempted = mutableListOf<String>()
        val dead = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                attempted += site.key
                return false
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                emptyList<HerrinSourceMovie>()
            override suspend fun details(candidate: HerrinSourceMovie): Film? = null
            override suspend fun canPlay(detailed: Film) = false
        }
        val all = (1..13).map(::site)
        val result = HerrinApprovedSpiderBatch.scanAcrossPages(
            request, all, { true }, dead)
        assertNull(result.match)
        assertEquals(13, result.checked)
        assertEquals(0, result.nextOffset)
        assertEquals((1..13).map { "node-$it" }, attempted)
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
