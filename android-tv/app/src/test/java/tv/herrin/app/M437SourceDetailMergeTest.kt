package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M437SourceDetailMergeTest {
    private val raw = Film("source-id", "原创剧 第二季【4K】", "来源副标题", "剧集", "2025", "来源简介", 0,
        artworkUrl = "https://source.example/old.jpg", spiderVodId = "original-spider-id", cmsVodId = "original-cms-id")
    private val metadata = EnrichedArtwork(
        "https://image.tmdb.org/t/p/w780/official.jpg", "https://image.tmdb.org/t/p/original/official-wide.jpg",
        synopsis = "官方原创简介", year = "2024", rating = 8.4, genres = listOf("科幻"), category = "剧集",
        originalTitle = "Original Series", actors = listOf("原创演员"), directors = listOf("原创导演"),
        regions = listOf("中国大陆"), releaseDate = "2024-01-02", runtimeMinutes = 42,
        people = listOf(FilmPerson("原创演员", "饰 航行者", "https://image.tmdb.org/t/p/w185/original.jpg")),
        tmdbTvId = 437, displayTitle = "官方原创剧名")
    private val detailJson = """{"list":[{"vod_id":"original-cms-id","vod_name":"来源详情名",
        "vod_pic":"https://source.example/latest.jpg","vod_content":"最新来源简介",
        "vod_year":"2026","type_name":"电视剧","vod_remarks":"来源更新到第2集",
        "vod_play_from":"合法原始线路","vod_play_url":"第1集${'$'}https://media.example/latest-1.mp4#第2集${'$'}https://media.example/latest-2.mp4"}]}"""

    @Test fun `Spider IPC detail completing after metadata keeps every current display field`() {
        val current = FilmMetadataEnrichment.apply(raw, metadata)
        // Exercise the real JSON IPC codec, which deliberately does not transmit enrichment.
        val requestSnapshot = SpiderIpc.readFilm(SpiderIpc.film(raw))
        val resolved = SpiderVodCodec.detail(detailJson, requestSnapshot)
        val ipcResponse = SpiderIpc.readFilm(SpiderIpc.film(resolved))
        assertNull(ipcResponse.metadataSource)
        val merged = FilmMetadataEnrichment.mergeSourceDetails(current, ipcResponse)
        assertEquals(current.copy(episodes = ipcResponse.episodes,
            playbackEpisodes = ipcResponse.playbackEpisodes), merged)
        assertEquals("官方原创剧名", merged.title)
        assertEquals(437, merged.tmdbTvId)
        assertEquals("原创剧 第二季【4K】", merged.originalDisplay!!.title)
        assertEquals(2, merged.playbackEpisodes.size)
        assertEquals("https://media.example/latest-2.mp4", merged.playbackEpisodes[1].lines.single().uri)
        assertEquals("original-spider-id", merged.spiderVodId)
        assertEquals("original-cms-id", merged.cmsVodId)
    }

    @Test fun `CMS detail based on enriched request cannot overwrite current official artwork`() {
        val current = FilmMetadataEnrichment.apply(raw, metadata)
        val details = TvBoxVodCodec.detail(detailJson, current, allowHttp = false)
        // Existing CMS copy keeps a TMDB flag while replacing fields with source values.
        assertEquals("TMDB", details.metadataSource)
        assertEquals("https://source.example/latest.jpg", details.artworkUrl)
        val merged = FilmMetadataEnrichment.mergeSourceDetails(current, details)
        assertEquals(current.copy(episodes = details.episodes,
            playbackEpisodes = details.playbackEpisodes), merged)
        assertEquals("https://image.tmdb.org/t/p/w780/official.jpg", merged.artworkUrl)
        assertEquals("官方原创简介", merged.synopsis)
        assertEquals("2024", merged.year)
        assertEquals("https://media.example/latest-1.mp4", merged.playbackEpisodes[0].lines.single().uri)
    }

    @Test fun `stale enriched detail cannot reenable metadata after user restored source display`() {
        val enriched = FilmMetadataEnrichment.apply(raw, metadata)
        val staleResponse = TvBoxVodCodec.detail(detailJson, enriched, allowHttp = false)
        val current = FilmMetadataEnrichment.restore(enriched).copy(synopsis = "用户当前看到的来源简介")
        val merged = FilmMetadataEnrichment.mergeSourceDetails(current, staleResponse)
        assertNull(merged.metadataSource)
        assertNull(merged.originalDisplay)
        assertEquals(current.copy(episodes = staleResponse.episodes,
            playbackEpisodes = staleResponse.playbackEpisodes), merged)
        assertEquals("https://source.example/old.jpg", merged.artworkUrl)
        assertEquals("https://media.example/latest-2.mp4", merged.playbackEpisodes[1].lines.single().uri)
    }

    @Test fun `without metadata latest source detail replaces old source fields`() {
        val details = TvBoxVodCodec.detail(detailJson, raw, allowHttp = false)
        assertEquals(details, FilmMetadataEnrichment.mergeSourceDetails(raw, details))
        assertEquals("最新来源简介", details.synopsis)
        assertEquals("2026", details.year)
    }

    @Test fun `mismatched selection is ignored and missing source ids may be filled`() {
        val current = FilmMetadataEnrichment.apply(raw.copy(spiderVodId = null, cmsVodId = null), metadata)
        val details = TvBoxVodCodec.detail(detailJson, raw, allowHttp = false)
        assertEquals(current, FilmMetadataEnrichment.mergeSourceDetails(current, details.copy(id = "unrelated")))
        val merged = FilmMetadataEnrichment.mergeSourceDetails(current, details)
        assertEquals("original-spider-id", merged.spiderVodId)
        assertEquals("original-cms-id", merged.cmsVodId)
        assertEquals(current.title, merged.title)
    }
}
