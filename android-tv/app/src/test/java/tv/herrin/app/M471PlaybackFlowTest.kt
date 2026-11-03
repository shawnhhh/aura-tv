package tv.herrin.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** M4.71: no network dependency, no demo fallback, strict NAS/cloud/CMS sequence. */
class M471PlaybackFlowTest {
    private val identity = HerrinFilmIdentity(157336, "movie",
        listOf("Interstellar"), 2014)

    private fun offer(id: String, source: String, type: HerrinSourceType,
                      quality: Int): HerrinSourceMovie =
        HerrinSourceMovie(source, type,
            Film(id, "Interstellar", "", "电影", "2014", "", R.drawable.photofilm_orbit),
            "movie", resolutionClaim = quality)

    private fun source(id: String, type: HerrinSourceType,
                       offering: HerrinSourceMovie, calls: MutableList<String>) =
        object : HerrinFilmSource {
            override val id = id
            override val type = type
            override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
                calls += id
                return listOf(offering)
            }
        }

    private fun playable(url: String) = PlaybackResult.Ready(PlaybackMedia(url))

    @Test fun verifiedNas1080OutranksCloud4kAndNeverQueriesTvbox() = runBlocking {
        val calls = mutableListOf<String>()
        val nas = offer("nas-1080", "nas", HerrinSourceType.NAS, 1080)
        val cloud = offer("cloud-4k", "cloud", HerrinSourceType.CLOUD, 2160)
        val cms = offer("cms-4k", "cms", HerrinSourceType.TVBOX_CMS, 2160)
        val resolver = HerrinVerifiedSourceResolver(listOf(
            source("cloud", HerrinSourceType.CLOUD, cloud, calls),
            source("cms", HerrinSourceType.TVBOX_CMS, cms, calls),
            source("nas", HerrinSourceType.NAS, nas, calls)))
        val result = resolver.find(identity,
            personalPlayback = { playable("https://authorized.example/" + it.film.id + ".mp4") },
            remoteDetails = { error("NAS was verified; should never open TVBox detail") })
        assertEquals(listOf("nas"), calls)
        assertEquals(nas, result.lookup.options.single())
        assertEquals(HerrinSourceTier.PERSONAL, result.lookup.selectedTier)
        assertEquals("https://authorized.example/nas-1080.mp4",
            result.personalPlaylist!!.mediaByLine.values.single().uri)
    }

    @Test fun unavailableNasContinuesToCloudWithoutQueryingTvbox() = runBlocking {
        val calls = mutableListOf<String>()
        val nas = offer("nas", "nas", HerrinSourceType.NAS, 2160)
        val cloud = offer("cloud", "cloud", HerrinSourceType.CLOUD, 1080)
        val cms = offer("cms", "cms", HerrinSourceType.TVBOX_CMS, 2160)
        val result = HerrinVerifiedSourceResolver(listOf(
            source("nas", HerrinSourceType.NAS, nas, calls),
            source("cloud", HerrinSourceType.CLOUD, cloud, calls),
            source("cms", HerrinSourceType.TVBOX_CMS, cms, calls)))
            .find(identity,
                personalPlayback = { if (it.sourceType == HerrinSourceType.NAS)
                    PlaybackResult.Unavailable("unreachable") else playable("https://cloud.example/" + it.film.id + ".m3u8") },
                remoteDetails = { error("Cloud should short circuit TVBox") })
        assertEquals(listOf("nas", "cloud"), calls)
        assertEquals(cloud, result.lookup.options.single())
        assertEquals(HerrinSourceTier.PERSONAL, result.lookup.selectedTier)
    }

    @Test fun bothPersonalSourcesFailThenRealCmsEpisodesAreRequired() = runBlocking {
        val calls = mutableListOf<String>()
        val nas = offer("nas", "nas", HerrinSourceType.NAS, 2160)
        val cloud = offer("cloud", "cloud", HerrinSourceType.CLOUD, 2160)
        val cms = offer("cms", "cms", HerrinSourceType.TVBOX_CMS, 1080)
        val film = cms.film.copy(playbackEpisodes = listOf(
            PlaybackEpisode(3, "S01E03", listOf(
                PlaybackLine("cms-real", "1080p", "https://authorized.example/03.m3u8")))))
        val result = HerrinVerifiedSourceResolver(listOf(
            source("cms", HerrinSourceType.TVBOX_CMS, cms, calls),
            source("cloud", HerrinSourceType.CLOUD, cloud, calls),
            source("nas", HerrinSourceType.NAS, nas, calls)))
            .find(identity, { PlaybackResult.Unavailable("not authorized") }) { film }
        assertEquals(listOf("nas", "cloud", "cms"), calls)
        assertEquals(HerrinSourceTier.TVBOX, result.lookup.selectedTier)
        assertEquals(3 to "cms-real", HerrinPlaybackEntry.firstPlayable(result.sourceDetails))
        assertNull(result.personalPlaylist)
    }

    @Test fun tvboxCandidateWithBlankEpisodeUrlMustFallThroughToNextCandidate() = runBlocking {
        val calls = mutableListOf<String>()
        val empty = offer("empty", "cms", HerrinSourceType.TVBOX_CMS, 2160)
        val valid = offer("valid", "cms", HerrinSourceType.TVBOX_CMS, 1080)
        val resolver = HerrinVerifiedSourceResolver(listOf(
            object : HerrinFilmSource {
                override val id = "cms"
                override val type = HerrinSourceType.TVBOX_CMS
                override suspend fun search(identity: HerrinFilmIdentity) = listOf(empty, valid)
            }
        ))
        val result = resolver.find(identity, { PlaybackResult.Unavailable("none") }) { candidate ->
            calls.add(candidate.film.id)
            candidate.film.copy(playbackEpisodes = listOf(
                PlaybackEpisode(1, "正片", listOf(PlaybackLine("line", "source",
                    if (candidate.film.id == "valid") "https://authorized.example/movie.mp4" else "")))))
        }
        assertEquals(listOf("empty", "valid"), calls)
        assertEquals("valid", result.lookup.options.single().film.id)
        assertEquals("line", HerrinPlaybackEntry.firstPlayable(result.sourceDetails)?.second)
    }

    @Test fun allDeadNasOffersDoNotPreventCloudFallback() = runBlocking {
        val calls = mutableListOf<String>()
        val nas = offer("nas-dead", "nas", HerrinSourceType.NAS, 2160)
        val cloud = offer("cloud-ok", "cloud", HerrinSourceType.CLOUD, 1080)
        val result = HerrinVerifiedSourceResolver(listOf(
            source("nas", HerrinSourceType.NAS, nas, calls),
            source("cloud", HerrinSourceType.CLOUD, cloud, calls)
        )).find(identity,
            personalPlayback = { playable("https://source.example/" + it.film.id + ".mkv") },
            verifyPersonal = { it.uri.contains("cloud-ok") },
            remoteDetails = { null })
        assertEquals(listOf("nas", "cloud"), calls)
        assertEquals("cloud-ok", result.lookup.options.single().film.id)
    }

    @Test fun personalProbeStopsAfterFirstVerifiedFileAndRetainsLaterEpisodes() = runBlocking {
        val calls = mutableListOf<String>()
        val candidates = (1..15).map { index ->
            offer("episode-$index", "nas", HerrinSourceType.NAS, 1080)
        }
        val adapter = object : HerrinFilmSource {
            override val id = "nas"
            override val type = HerrinSourceType.NAS
            override suspend fun search(identity: HerrinFilmIdentity) = candidates
        }
        var probeCount = 0
        val result = HerrinVerifiedSourceResolver(listOf(adapter)).find(
            identity,
            personalPlayback = { playable("https://authorized.example/" + it.film.id + ".mp4") },
            verifyPersonal = { probeCount++; true },
            remoteDetails = { null })
        assertEquals(1, probeCount)
        assertEquals(HerrinSourceTier.PERSONAL, result.lookup.selectedTier)
        assertEquals(15, result.lookup.options.size)
        // The UI intentionally caps one episode at 12 lines, even when
        // lookup correctly keeps all 15 discovered media offers.
        assertEquals(12, result.personalPlaylist!!.mediaByLine.size)
    }

    @Test fun probeSkipsBrokenHighestQualityAndContinuesOnException() = runBlocking {
        val offers = listOf(
            offer("first-broken", "nas", HerrinSourceType.NAS, 2160),
            offer("second-throws", "nas", HerrinSourceType.NAS, 1080),
            offer("third-ready", "nas", HerrinSourceType.NAS, 720))
        val visited = mutableListOf<String>()
        val result = HerrinVerifiedSourceResolver(listOf(
            object : HerrinFilmSource {
                override val id = "nas"
                override val type = HerrinSourceType.NAS
                override suspend fun search(identity: HerrinFilmIdentity) = offers
            }
        )).find(identity,
            personalPlayback = { playable("https://authorized.example/" + it.film.id + ".mp4") },
            verifyPersonal = {
                val id = it.uri.substringAfterLast('/').substringBefore(".mp4")
                visited += id
                when(id) {
                    "first-broken" -> false
                    "second-throws" -> error("WebDAV gateway temporarily unavailable")
                    else -> true
                }
            },
            remoteDetails = { null })
        assertEquals(listOf("first-broken", "second-throws", "third-ready"), visited)
        assertEquals(listOf("third-ready"), result.lookup.options.map { it.film.id })
        assertEquals(1, result.personalPlaylist!!.episodes.size)
    }

    @Test fun eightDeadFileProbeBudgetFallsBackToCloud() = runBlocking {
        val calls = mutableListOf<String>()
        val dead = (1..13).map { offer("dead-$it", "nas", HerrinSourceType.NAS, 2160) }
        val cloud = offer("ready-cloud", "cloud", HerrinSourceType.CLOUD, 720)
        val adapters = listOf(
            object : HerrinFilmSource {
                override val id = "nas"
                override val type = HerrinSourceType.NAS
                override suspend fun search(identity: HerrinFilmIdentity) =
                    dead.also { calls += "nas" }
            },
            source("cloud", HerrinSourceType.CLOUD, cloud, calls)
        )
        var failedProbeCount = 0
        val result = HerrinVerifiedSourceResolver(adapters).find(
            identity,
            personalPlayback = { playable("https://authorized.example/" + it.film.id + ".mp4") },
            verifyPersonal = {
                if (it.uri.contains("/dead-")) {
                    failedProbeCount++
                    false
                } else true
            },
            remoteDetails = { null })
        assertEquals(8, failedProbeCount)
        assertEquals(listOf("nas", "cloud"), calls)
        assertEquals("ready-cloud", result.lookup.options.single().film.id)
    }

    @Test fun tvboxSourceWithEpisodesButBrokenProxyIsExcludedForNextNode() = runBlocking {
        val inspected = mutableListOf<String>()
        val broken = offer("broken-route", "cms", HerrinSourceType.TVBOX_CMS, 2160)
        val ready = offer("ready-route", "cms", HerrinSourceType.TVBOX_CMS, 1080)
        val source = object : HerrinFilmSource {
            override val id = "cms"
            override val type = HerrinSourceType.TVBOX_CMS
            override suspend fun search(identity: HerrinFilmIdentity) = listOf(broken, ready)
        }
        val result = HerrinVerifiedSourceResolver(listOf(source)).find(
            identity,
            personalPlayback = { PlaybackResult.Unavailable("not personal") },
            verifyRemote = { offer, _ ->
                inspected.add(offer.film.id)
                offer.film.id != "broken-route"
            },
            remoteDetails = { candidate ->
                candidate.film.copy(playbackEpisodes = listOf(
                    PlaybackEpisode(1, "正片", listOf(
                        PlaybackLine("direct", "线路", "https://authorized.example/movie.mp4")))))
            })
        assertEquals(listOf("broken-route", "ready-route"), inspected)
        assertEquals("ready-route", result.lookup.options.single().film.id)
        assertEquals("direct", HerrinPlaybackEntry.firstPlayable(result.sourceDetails)?.second)
    }

    @Test fun verifiedRemoteReturnsExactlyTheEpisodeAndLineThatPassedMediaByteProbe() = runBlocking {
        val film = offer("film", "cms", HerrinSourceType.TVBOX_CMS, 2160)
        val calls = mutableListOf<String>()
        val result = HerrinVerifiedSourceResolver(listOf(
            object : HerrinFilmSource {
                override val id = "cms"
                override val type = HerrinSourceType.TVBOX_CMS
                override suspend fun search(identity: HerrinFilmIdentity) = listOf(film)
            }
        )).find(identity,
            personalPlayback = { PlaybackResult.Unavailable("not personal") },
            verifyRemote = { _, _ -> calls.add("probe verified"); true },
            selectRemote = { _, _ -> 2 to "working-backup" },
            remoteDetails = { offer -> offer.film.copy(playbackEpisodes = listOf(
                PlaybackEpisode(1, "首集", listOf(PlaybackLine("broken", "4K", "https://example.test/404"))),
                PlaybackEpisode(2, "可播放", listOf(PlaybackLine(
                    "working-backup", "1080p", "https://example.test/ready.mp4")))))
            })
        assertEquals(listOf("probe verified"), calls)
        assertEquals(2 to "working-backup", result.verifiedLine)
        assertEquals("film", result.lookup.options.single().film.id)
    }

    @Test fun tmdbNeverUsesFakeEpisodeOrEmptyUriAsPlayable() {
        val posterOnly = Film("tmdb-movie-157336", "Interstellar", "",
            "电影", "2014", "", R.drawable.photofilm_orbit)
        assertTrue(HerrinPlaybackEntry.unavailableTmdb(posterOnly))
        assertNull(HerrinPlaybackEntry.firstPlayable(posterOnly))
        val empty = posterOnly.copy(playbackEpisodes = listOf(
            PlaybackEpisode(1, "正片", listOf(PlaybackLine("blank", "unknown", "")))))
        assertTrue(HerrinPlaybackEntry.unavailableTmdb(empty))
        val real = posterOnly.copy(playbackEpisodes = listOf(
            PlaybackEpisode(2, "第二集", listOf(
                PlaybackLine("missing", "broken", ""),
                PlaybackLine("real", "HLS", "https://authorized.example/episode2.m3u8")))))
        assertFalse(HerrinPlaybackEntry.unavailableTmdb(real))
        assertEquals(2 to "real", HerrinPlaybackEntry.firstPlayable(real))
    }

    @Test fun sourceFallbackHonorsHttpAuthAndRetainsIndividualHlsMime() {
        val session = PlaybackFormatRetry.Session(
            "https://authorized.example/source.m3u8",
            listOf("https://authorized.example/backup.mp4"), null,
            allowNotFoundFallback = true)
        assertEquals(PlaybackFormatRetry.HLS, session.current.mime)
        assertNull(session.next(2004, 403))
        val retry = PlaybackFormatRetry.Session(
            "https://authorized.example/source.m3u8",
            listOf("https://authorized.example/backup.mp4"), null,
            allowNotFoundFallback = true)
        val next = retry.next(2004, 404)
        assertEquals("https://authorized.example/backup.mp4", next!!.uri)
        assertNull(next.mime) // HLS cannot leak onto an MP4 backup.
        assertNull(retry.next(2004, 404))
    }
}
