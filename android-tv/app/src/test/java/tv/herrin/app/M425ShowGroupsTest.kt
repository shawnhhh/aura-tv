package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M425ShowGroupsTest {
    private fun film(id: String, title: String, year: String = "2023",
                     category: String = "动漫", tmdb: Int? = null,
                     episodes: List<PlaybackEpisode> = emptyList()) =
        Film(id, title, "", category, year, "", 0, playbackEpisodes = episodes,
            cmsVodId = id, tmdbTvId = tmdb)

    private fun ep(index: Int, title: String, vararg lines: PlaybackLine) =
        PlaybackEpisode(index, title, lines.toList())

    private fun line(id: String, title: String, suffix: String) =
        PlaybackLine(id, title, "https://media.example.test/$suffix.mp4")

    @Test fun strictSeparateFilmsProduceOneCanonicalCoverAndCorrectOriginalRequests() {
        val a = film("a", "原创剧 第1季", episodes = listOf(
            ep(1, "01", line("cms-0", "线路A", "a1"))))
        val b = film("b", "原创剧 第二季", episodes = listOf(
            ep(1, "01", line("cms-0", "线路B", "b1")),
            ep(3, "03", line("cms-0", "线路B", "b3"))))
        val catalog = ShowCatalog.build(listOf(a, b), "tvbox:one")
        assertEquals(1, catalog.groups.size)
        assertEquals(listOf("原创剧"), catalog.collapse(listOf(a, b)).map { it.title })
        val group = catalog.groups.single()
        val second = GroupSeasonIndex.from(group).episode(2, 3)!!.sources.single()
        assertEquals(PlaybackRequest("b", 3, "cms-0"), second.request())
        assertEquals("https://media.example.test/b3.mp4", second.line.uri)
        assertEquals("b", second.film.id)
    }

    @Test fun chineseNumberSeasonsAreStrictAndSpecialsNotSilentlyMerged() {
        assertEquals("原创新剧" to 1, ShowGroupMatcher.titleParts("原创新剧 第一季"))
        assertEquals("原创新剧" to 2, ShowGroupMatcher.titleParts("原创新剧 第二季"))
        assertEquals("原创新剧" to 21, ShowGroupMatcher.titleParts("原创新剧 第二十一季"))
        assertEquals(10, ShowGroupMatcher.seasonNumber("十"))
        assertEquals(20, ShowGroupMatcher.seasonNumber("二十"))
        assertNull(ShowGroupMatcher.seasonNumber("二百"))
        assertEquals(1, ShowCatalog.build(listOf(
            film("a", "原创新剧 第一季"), film("b", "原创新剧 第二季")), "scope").groups.size)
        assertTrue(ShowCatalog.build(listOf(
            film("a", "原创新剧 OVA 第一季"), film("b", "原创新剧 第二季")), "scope").groups.isEmpty())
        assertEquals("Space" to 2, ShowGroupMatcher.titleParts("Space 第二季"))
        assertEquals(1, ShowCatalog.build(listOf(
            film("a", "Space 第一季"), film("b", "Space 第二季")), "scope").groups.size)
    }

    @Test fun seasonThreeSearchReturnsWholeSeriesEvenIfCoverChosenFromFirstSeason() {
        val a = film("a", "原创剧 第一季")
        val b = film("b", "原创剧 第3季")
        val catalog = ShowCatalog.build(listOf(a, b), "site:A")
        assertEquals(1, catalog.search("第3季", "全部", false).size)
        assertEquals("原创剧", catalog.search("第3季", "全部", false).single().title)
        assertEquals(1, catalog.collapse(listOf(b, a)).size)
        assertEquals(1, catalog.favorites(setOf("b")).size)
        assertEquals("原创剧", catalog.favorites(setOf("b")).single().title)
    }

    @Test fun groupKeyStableAcrossReorderingAndTmdbEnrichmentOfSameName() {
        val a = film("entry-10", "原创剧 第一季", tmdb = null)
        val b = film("entry-11", "原创剧 第2季", tmdb = null)
        val before = ShowCatalog.build(listOf(a, b), "scope1").groups.single().key
        val reordered = ShowCatalog.build(listOf(b.copy(id = "entry-0", tmdbTvId = 123),
            a.copy(id = "entry-1", tmdbTvId = 123)), "scope1")
        assertEquals(before, reordered.groups.single().key)
        assertNotEquals(before, ShowCatalog.build(listOf(a, b), "scope2").groups.single().key)
    }

    @Test fun memberIdentityChangesOnNewSourceButNotOnMetadataOrEpisodeEnrichment() {
        val a = film("a", "原创剧 第一季", tmdb = 777)
        val b = film("b", "原创剧 第二季", tmdb = 777)
        val initial = ShowCatalog.build(listOf(a, b), "scope").groups.single()
        val refreshed = ShowCatalog.build(listOf(b.copy(year = "2026", episodes = listOf(
            ep(2, "02", line("b", "备用", "b2")))), a.copy(year = "2026")), "scope").groups.single()
        assertEquals(initial.key, refreshed.key)
        assertEquals(initial.memberIds, refreshed.memberIds)
        val added = ShowCatalog.build(listOf(a, b, film("c", "原创剧 第三季", tmdb = 777)),
            "scope").groups.single()
        assertEquals(initial.key, added.key)
        assertNotEquals(initial.memberIds, added.memberIds)
        assertEquals(listOf("a", "b", "c"), added.memberIds)
    }

    @Test fun exactSharedTmdbIdAllowsOtherLanguageAliasButDifferentIdsNeverMerge() {
        val a = film("a", "原名 第1季", tmdb = 777)
        val b = film("b", "Original Title S02", tmdb = 777)
        assertEquals(1, ShowCatalog.build(listOf(a, b), "one").groups.size)
        assertEquals(777, ShowCatalog.build(listOf(a, b), "one").groups.single().tmdbTvId)
        assertTrue(ShowCatalog.build(listOf(a, b.copy(tmdbTvId = 778)), "one").groups.isEmpty())
    }

    @Test fun movieSpecialAndDistantRemakeNeverMergeBySimilarTitle() {
        val one = film("a", "同名剧 第一季", year = "1960")
        val remake = film("b", "同名剧 第二季", year = "2026")
        val movie = film("c", "同名剧 第三季", category = "电影")
        val ova = film("d", "同名剧 OVA 第四季")
        assertTrue(ShowCatalog.build(listOf(one, remake, movie, ova), "source").groups.isEmpty())
    }

    @Test fun onlyOneExplicitSeasonAndUnlabeledSiblingCannotTextMergeWithoutTrustedId() {
        val first = film("a", "原创剧 第一季")
        val unnumbered = film("b", "原创剧")
        assertTrue(ShowCatalog.build(listOf(first, unnumbered), "scope").groups.isEmpty())
        assertEquals(1, ShowCatalog.build(listOf(first.copy(tmdbTvId = 77),
            unnumbered.copy(tmdbTvId = 77)), "scope").groups.size)
    }

    @Test fun existingM424TwoSeasonLinesPlusSeparateThirdSeasonNeverCrossWire() {
        val a = film("a", "原创剧", episodes = listOf(
            ep(1, "第1集", line("s1", "第1季", "s1e1"), line("s2", "第2季", "s2e1")),
            ep(2, "第2集", line("s1", "第1季", "s1e2"), line("s2", "第2季", "s2e2"))))
        val b = film("b", "原创剧 第3季", episodes = listOf(
            ep(1, "1集", line("a", "高清", "s3e1"))))
        val group = ShowCatalog.build(listOf(a, b), "same-site").groups.single()
        val seasons = GroupSeasonIndex.from(group)
        assertEquals(listOf(1, 2, 3), seasons.sections.map { it.number })
        assertEquals("s2e2", seasons.episode(2, 2)!!.sources.single().line.uri.substringAfterLast('/').substringBefore('.'))
        assertEquals(PlaybackRequest("b", 1, "a"), seasons.episode(3, 1)!!.sources.single().request())
    }

    @Test fun initiallyEmptyListingsCanBeEnrichedOnDemandWithoutChangingGroupIdentity() {
        val a = film("a", "原创剧 第一季")
        val b = film("b", "原创剧 第二季")
        val pending = ShowCatalog.build(listOf(a, b), "scope")
        val key = pending.groups.single().key
        assertEquals(listOf("a", "b"), GroupSeasonIndex.detailsNeeded(pending.groups.single()).map { it.id })
        assertTrue(GroupSeasonIndex.from(pending.groups.single()).sections.all { it.episodes.isEmpty() })
        val resolvedA = a.copy(playbackEpisodes = listOf(ep(1, "01", line("a", "线路1", "s1e1"))))
        val resolvedB = b.copy(playbackEpisodes = listOf(ep(3, "03", line("b", "线路2", "s2e3"))))
        val ready = ShowCatalog.build(listOf(resolvedB, resolvedA), "scope").groups.single()
        assertEquals(key, ready.key)
        val source = GroupSeasonIndex.from(ready).episode(2, 3)!!.sources.single()
        assertEquals(PlaybackRequest("b", 3, "b"), source.request())
    }

    @Test fun sameEpisodeFromDifferentEntriesKeepsAlternativeSourceLinesAndHistory() {
        val a = film("a", "原创剧 第1季", tmdb = 888,
            episodes = listOf(ep(1, "01", line("a1", "高清", "a1"))))
        val b = film("b", "原创剧 第1季", tmdb = 888,
            episodes = listOf(ep(1, "01", line("b1", "备用", "b1"))))
        val c = film("c", "原创剧 第2季", tmdb = 888,
            episodes = listOf(ep(1, "01", line("c1", "高清", "c1"))))
        val group = ShowCatalog.build(listOf(a, b, c), "scope").groups.single()
        val layout = GroupSeasonIndex.from(group)
        val sameEpisode = layout.episode(1, 1)!!
        assertEquals(setOf("a", "b"), sameEpisode.sources.map { it.film.id }.toSet())
        val last = GroupSeasonIndex.latest(layout) { ref ->
            when (ref.film.id) {
                "a" -> WatchPosition(20_000, 90_000, 100)
                "b" -> WatchPosition(40_000, 90_000, 300)
                else -> null
            }
        }!!
        assertEquals("b", last.first.film.id)
        assertEquals(PlaybackRequest("b", 1, "b1"), last.first.request())
        val progress = ShowCatalog.build(listOf(a, b, c), "scope").watchProgress(a) { f, _, _ ->
            if (f.id == "b") WatchPosition(40_000, 90_000, 300) else null
        }
        assertEquals(300L, progress?.updatedAtMs)
    }

    @Test fun verifiedTmdbIdCanGroupTwoVersionsOfSameSeasonWithoutInventingAnotherSeason() {
        val a = film("a", "原创剧 第一季", tmdb = 999,
            episodes = listOf(ep(1, "01", line("a1", "1080P", "v1"))))
        val b = film("b", "原创剧 第一季", tmdb = 999,
            episodes = listOf(ep(1, "01", line("b1", "4K", "v2"))))
        val group = ShowCatalog.build(listOf(a, b), "scope").groups.single()
        val seasons = GroupSeasonIndex.from(group)
        assertEquals(listOf(1), seasons.sections.map { it.number })
        assertEquals(2, seasons.episode(1, 1)!!.sources.size)
        assertEquals(setOf("a", "b"), seasons.episode(1, 1)!!.sources.map { it.film.id }.toSet())
        val unlabeled = ShowCatalog.build(listOf(
            film("u1", "另一原名", tmdb = 1000),
            film("u2", "Different alias", tmdb = 1000)), "scope").groups.single()
        assertTrue(GroupSeasonIndex.from(unlabeled).sections.isEmpty())
    }
}
