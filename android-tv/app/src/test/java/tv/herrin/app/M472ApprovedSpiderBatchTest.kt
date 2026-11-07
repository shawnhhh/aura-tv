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
                // The catalog claim alone must never be treated as verified 4K.
                // Only node-9 exposes a real selected 4K-labelled playback line.
                val lineLabel = if (active == "node-9") "2160p" else "720p"
                return fakePoster.copy(playbackEpisodes = listOf(
                    PlaybackEpisode(1, "正片", listOf(PlaybackLine(
                        "line", lineLabel, "https://authorized.example/video.mp4")))))
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

    @Test fun restoredWinnerNeverRetainsStale4kWhenLiveLineDowngrades() = runBlocking {
        var activated = ""
        var nodeOneDetailReads = 0
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                activated = site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, if (site.key == "node-1") 2160 else 1080))
            override suspend fun details(candidate: HerrinSourceMovie): Film {
                val quality = if (activated == "node-1") {
                    nodeOneDetailReads++
                    if (nodeOneDetailReads == 1) "4K" else "720p"
                } else "1080p"
                return fakePoster.copy(playbackEpisodes = listOf(
                    PlaybackEpisode(1, "正片", listOf(PlaybackLine(
                        "live", quality, "https://authorized.example/film.mp4")))))
            }
            override suspend fun canPlay(detailed: Film) = true
        }
        val result = HerrinApprovedSpiderBatch.scan(request, listOf(site(1)),
            { true }, session)
        assertEquals("node-1", result.match?.site?.key)
        assertEquals(720, result.match?.claim)
        assertEquals("live", result.match?.line)
    }

    /**
     * A restored provider may downgrade its selected line after a Spider
     * session change. An already verified 1080p source must then win over
     * the revalidated 720p line; the returned source must be the active one.
     */
    @Test fun restoredFourKThatFallsTo720MustNotDisplaceStable1080() = runBlocking {
        val detailReads = mutableMapOf<String, Int>()
        var active = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                active = site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, if (site.key == "node-1") 2160 else 1080))
            override suspend fun details(candidate: HerrinSourceMovie): Film {
                assertEquals(candidate.sourceId.substringAfter("spider:"), active)
                val reads = (detailReads[active] ?: 0) + 1
                detailReads[active] = reads
                val label = when {
                    active == "node-1" && reads == 1 -> "2160p"
                    active == "node-1" -> "720p"
                    else -> "1080p"
                }
                return fakePoster.copy(playbackEpisodes = listOf(
                    PlaybackEpisode(1, "正片", listOf(PlaybackLine(
                        "verified", label, "https://authorized.example/video.mp4")))))
            }
            override suspend fun canPlay(detailed: Film) = true
        }

        val result = HerrinApprovedSpiderBatch.scan(request,
            listOf(site(1), site(2)), { true }, session)
        assertEquals("node-2", result.match?.site?.key)
        assertEquals(1080, result.match?.claim)
        assertEquals("node-2", active)
    }

    /**
     * A search hit claiming 4K can actually expose only a validated 720p
     * playback line. Another hit from the SAME trusted site must still be
     * considered when its selected media line is genuinely better.
     */
    @Test fun sameSitePicksHighestVerifiedLineNotFirstPlayableCatalogClaim() = runBlocking {
        val inspectedClaims = mutableListOf<Int>()
        var currentSite = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                currentSite = site.key
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                listOf(candidate(site, 2160), candidate(site, 1080))
            override suspend fun details(candidate: HerrinSourceMovie): Film {
                assertEquals("node-1", currentSite)
                val catalogClaim = candidate.resolutionClaim ?: 0
                inspectedClaims += catalogClaim
                val lineLabel = if (catalogClaim == 2160) "720p" else "1080p"
                return fakePoster.copy(playbackEpisodes = listOf(
                    PlaybackEpisode(1, "正片", listOf(PlaybackLine(
                        "verified", lineLabel, "https://authorized.example/video.mp4")))))
            }
            override suspend fun canPlay(detailed: Film) = true
        }

        val result = HerrinApprovedSpiderBatch.scan(request, listOf(site(1)),
            { true }, session)
        assertEquals(1080, result.match?.claim)
        assertEquals(1080, result.match?.candidate?.resolutionClaim)
        assertEquals("node-1", result.match?.site?.key)
        assertEquals("node-1", currentSite)
        assertEquals(listOf(2160, 1080, 1080), inspectedClaims)
    }

    @Test fun singleLookupMustAutomaticallyReachAllFortyFiveApprovedDeadNodes() = runBlocking {
        val attempted = mutableListOf<String>()
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                attempted += site.key
                return false
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                emptyList<HerrinSourceMovie>()
            override suspend fun details(candidate: HerrinSourceMovie): Film? = null
            override suspend fun canPlay(detailed: Film) = false
        }
        val eligible = (1..45).map(::site)
        val progressCounts = mutableListOf<Int>()
        val result = HerrinApprovedSpiderBatch.scanAcrossPages(request,
            eligible, { true }, session,
            onProgress = { progressCounts += it.checked })
        assertEquals(listOf(6, 12, 18, 24, 30, 36, 42, 45), progressCounts)
        assertNull(result.match)
        assertEquals("A single film-detail lookup must not stop after 6 or 24/45 sites",
            45, result.checked)
        assertEquals(45, result.eligible)
        assertEquals(0, result.nextOffset)
        assertEquals((1..45).map { "node-$it" }, attempted)
    }

    @Test fun autoFindStopsAtFirstActuallyPlayableLaterSiteAndPreservesLiveSession() = runBlocking {
        val visited = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val eachSite = mutableListOf<Int>()
        var active = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                active = site.key
                visited += active
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                if (site.key == "node-8") listOf(candidate(site, 720))
                else emptyList<HerrinSourceMovie>()
            override suspend fun details(candidate: HerrinSourceMovie): Film? {
                assertEquals(candidate.sourceId.substringAfter("spider:"), active)
                return playable()
            }
            override suspend fun canPlay(detailed: Film) = true
        }
        val result = HerrinApprovedSpiderBatch.scanAcrossPages(
            request, (1..45).map(::site), { true }, session,
            stopAtFirstPlayable = true,
            onProgress = { progress += it.checked },
            onSiteProgress = { eachSite += it.checked })
        assertEquals("node-8", result.match?.site?.key)
        assertEquals("node-8", active)
        assertEquals(8, result.checked)
        assertEquals(45, result.eligible)
        assertEquals(8, result.nextOffset)
        assertEquals(listOf(6, 8), progress)
        assertEquals((1..8).toList(), eachSite)
        assertFalse("Stop immediately at the first byte-verified source",
            visited.contains("node-9"))
    }

    @Test fun nextSiteContinuesWhenPreviousApprovedSiteExceedsItsTimeout() = runBlocking {
        val visited = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val eachSite = mutableListOf<Int>()
        var active = ""
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite): Boolean {
                visited += site.key
                active = site.key
                if (site.key == "node-1") {
                    kotlinx.coroutines.delay(2_000L) // bounded by scan's >= 1s floor
                }
                return true
            }
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                if (site.key == "node-2") listOf(candidate(site, 1080))
                else emptyList()
            override suspend fun details(candidate: HerrinSourceMovie): Film? = playable()
            override suspend fun canPlay(detailed: Film) = active == "node-2"
        }
        val started = System.nanoTime()
        val result = HerrinApprovedSpiderBatch.scan(request, listOf(site(1), site(2)),
            { true }, session, timeoutPerSiteMs = 1_000L, stopAtFirstPlayable = true,
            onSiteProgress = { progress += it.checked })
        val durationMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals("node-2", result.match?.site?.key)
        assertEquals(listOf(1, 2), progress)
        assertEquals(listOf("node-1", "node-2"), visited)
        assertTrue("A cancellable slow provider must not hold up a working one", durationMs < 1_900L)
    }

    @Test fun reportsSafePerNodeOutcomeRatherThanClaimingNoSourcesFound() = runBlocking {
        val outcomes = mutableListOf<HerrinApprovedSpiderBatch.Outcome?>()
        val session = object : HerrinApprovedSpiderBatch.Session {
            override suspend fun activate(site: TvBoxSite) = site.key != "node-1"
            override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                emptyList<HerrinSourceMovie>()
            override suspend fun details(candidate: HerrinSourceMovie): Film? = null
            override suspend fun canPlay(detailed: Film) = false
        }
        HerrinApprovedSpiderBatch.scan(
            request, listOf(site(1), site(2)), { true }, session,
            onSiteProgress = { outcomes += it.lastOutcome })
        assertEquals(listOf(
            HerrinApprovedSpiderBatch.Outcome.ACTIVATE_FAILED,
            HerrinApprovedSpiderBatch.Outcome.NO_MATCH
        ), outcomes)
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
