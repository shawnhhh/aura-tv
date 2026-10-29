package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M424SeasonEpisodesTest {
    private fun film(episodes: List<PlaybackEpisode>, id: String = "source-film-32") =
        Film(id, "授权剧集", "", "剧集", "2026", "", 0, playbackEpisodes = episodes)
    private fun line(id: String, title: String = "高清", path: String = id) =
        PlaybackLine(id, title, "https://legal.example.test/$path.m3u8")

    @Test fun acceptsThreeStrictExplicitSeasonEpisodeFormatsWithoutChangingSourceIndex() {
        val original = film(listOf(
            PlaybackEpisode(41, "S02E05", listOf(line("a"))),
            PlaybackEpisode(42, "第3季第1集", listOf(line("b"))),
            PlaybackEpisode(43, "Season 3 / Episode 2", listOf(line("c")))
        ))
        val layout = SeasonEpisodes.fromFilm(original)!!
        assertEquals(listOf(2, 3), layout.sections.map { it.number })
        assertEquals(listOf(1, 2), layout.sections[1].episodes.map { it.number })
        val third = layout.find(3, 2)!!
        assertEquals(43, third.source.number)
        assertEquals(PlaybackRequest(original.id, 43, "c"), SeasonPlayback.target(original, third, third.lines[0]))
        assertEquals("https://legal.example.test/c.m3u8",
            ((LicensedPlaybackResolver.resolve(original, 43, "c") as PlaybackResult.Ready).media.uri))
    }

    @Test fun cmsSourceLinesActAsSeparateSeasonsWithNoWrongSeasonBackupLine() {
        val original = film(listOf(
            PlaybackEpisode(1, "01", listOf(line("cms-0", "第2季", "two-1"), line("cms-1", "第3季", "three-1"))),
            PlaybackEpisode(2, "2集", listOf(line("cms-0", "第2季", "two-2"), line("cms-1", "第3季", "three-2")))
        ))
        val layout = SeasonEpisodes.fromFilm(original)!!
        assertEquals(listOf(2, 3), layout.sections.map { it.number })
        val seasonTwo = layout.find(2, 1)!!
        val seasonThree = layout.find(3, 1)!!
        assertEquals(listOf("cms-0"), seasonTwo.lines.map { it.id })
        assertEquals(listOf("cms-1"), seasonThree.lines.map { it.id })
        assertEquals(1, seasonTwo.source.number)
        assertEquals(1, seasonThree.source.number)
        assertEquals("https://legal.example.test/three-1.m3u8",
            (LicensedPlaybackResolver.resolve(original, 1,
                SeasonPlayback.target(original, seasonThree, seasonThree.lines.first()).line)
                as PlaybackResult.Ready).media.uri)
    }

    @Test fun historySelectionIsIsolatedByLineForOverlappingSourceEpisodeIndexes() {
        val original = film(listOf(PlaybackEpisode(1, "第01集", listOf(
            line("cms-0", "第2季"), line("cms-1", "第3季")))))
        val layout = SeasonEpisodes.fromFilm(original)!!
        val bookmarks = mapOf((1 to "cms-0") to WatchPosition(14_000, 120_000, 100),
            (1 to "cms-1") to WatchPosition(294_000, 1_420_000, 300))
        val latest = SeasonPlayback.latest(layout) { ep, lineId -> bookmarks[ep to lineId] }!!
        assertEquals(3, latest.entry.season)
        assertEquals("cms-1", latest.line.id)
        assertEquals(294_000, latest.position.positionMs)
        assertEquals("04:54", formatSeasonClock(latest.position.positionMs))
        assertEquals("23:40", formatSeasonClock(latest.position.durationMs))
    }

    @Test fun oneSeasonOrUnlabelledPlaybackRetainsLegacyPicker() {
        val noSeason = film(listOf(PlaybackEpisode(1, "第1集", listOf(line("a", "线路1"))),
            PlaybackEpisode(2, "第2集", listOf(line("b", "线路2")))))
        assertNull(SeasonEpisodes.fromFilm(noSeason))
        assertNull(SeasonEpisodes.fromFilm(film(listOf(
            PlaybackEpisode(11, "第3季第5集", listOf(line("c"))),
            PlaybackEpisode(12, "第3季第6集", listOf(line("d")))))))
    }

    @Test fun malformedAndContradictoryLabelsCannotFabricateSeasons() {
        assertNull(SeasonEpisodes.explicitEpisode("第2季可能第5集"))
        assertNull(SeasonEpisodes.explicitEpisode("S02E0abc"))
        assertNull(SeasonEpisodes.explicitSeason("线路：第3季"))
        assertNull(SeasonEpisodes.explicitEpisode("第0季第1集"))
        val bad = film(listOf(
            PlaybackEpisode(1, "第2季第1集", listOf(line("a", "第3季"))),
            PlaybackEpisode(2, "第3季第1集", listOf(line("b", "第2季")))))
        assertNull(SeasonEpisodes.fromFilm(bad))
    }

    @Test fun separateFilmsAndDifferentCatalogSourcesNeverMergeByFuzzyName() {
        // The current safe M4.24 model never merges different Film objects; the title
        // alone (even exactly matching across two catalogs) grants no playback authority.
        val sourceA = film(listOf(PlaybackEpisode(1, "第2季第1集", listOf(line("a")))), "cms-one")
        val sourceB = film(listOf(PlaybackEpisode(1, "第3季第1集", listOf(line("b")))), "cms-two")
        assertNull(SeasonEpisodes.fromFilm(sourceA))
        assertNull(SeasonEpisodes.fromFilm(sourceB))
        assertEquals("cms-one", sourceA.id)
        assertEquals("cms-two", sourceB.id)
        assertNotEquals(sourceA.playbackEpisodes[0].lines[0].uri, sourceB.playbackEpisodes[0].lines[0].uri)
    }

    @Test fun selectedLineRetainsExistingIdentityAndDirectPlayNeedsNoSecondAction() {
        val original = film(listOf(
            PlaybackEpisode(9, "S02E01", listOf(line("backup-1"), line("backup-2"))),
            PlaybackEpisode(17, "S03E01", listOf(line("backup-3")))))
        val layout = SeasonEpisodes.fromFilm(original)!!
        val entry = layout.find(2, 1)!!
        val request = SeasonPlayback.target(original, entry, entry.preferredLine("backup-2"))
        assertEquals(PlaybackRequest("source-film-32", 9, "backup-2"), request)
        assertTrue(LicensedPlaybackResolver.resolve(original, request.episode, request.line) is PlaybackResult.Ready)
    }

    @Test fun realTvBoxCmsMergedPlaylistKeepsSeasonSpecificAddresses() {
        val json = """{"list":[{"vod_id":"42","vod_name":"原创剧情",
          "vod_play_from":"第2季${'$'}${'$'}${'$'}第3季",
          "vod_play_url":"01${'$'}https://legal.example.test/s2e1.mp4#02${'$'}https://legal.example.test/s2e2.mp4${'$'}${'$'}${'$'}1集${'$'}https://legal.example.test/s3e1.mp4#2集${'$'}https://legal.example.test/s3e2.mp4"}]}"""
        val original = TvBoxVodCodec.decode(json).single()
        val layout = SeasonEpisodes.fromFilm(original)!!
        assertEquals(listOf(2, 3), layout.sections.map { it.number })
        val entry = layout.find(3, 2)!!
        assertEquals("cms-1", entry.lines.single().id)
        val request = SeasonPlayback.target(original, entry, entry.lines.single())
        assertEquals("https://legal.example.test/s3e2.mp4",
            (LicensedPlaybackResolver.resolve(original, request.episode, request.line) as PlaybackResult.Ready).media.uri)
    }
}
