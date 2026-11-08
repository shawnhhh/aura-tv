package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class P0ResponseRelevanceTest {
    private val target = HerrinFilmIdentity(1, "movie", listOf("奥德赛", "The Odyssey"), 2026)
    private fun movie(title: String, year: String = "2026", type: String = "movie") =
        HerrinSourceMovie("site", HerrinSourceType.TVBOX_CMS,
            Film("vod", title, "", "电影", year, "", R.drawable.photofilm_orbit, cmsVodId = "opaque"), type)

    @Test fun unrelatedProviderResultsAreRetainedButNeverConfirmed() = runBlocking {
        val offers = listOf("奥德赛", "生化危机：爆发夜", "后会无期", "八仙过大海").map { movie(it) }
        val node = HerrinSourceDiscovery.Node("a", "a", HerrinSourceType.TVBOX_CMS,
            { offers }, { error("No automatic details") }, identity = target)
        var last = HerrinSourceDiscovery.Snapshot("", 0)
        HerrinSourceDiscovery().search("query-v1", listOf(node), null, Mutex()) { last = it }
        assertEquals(4, last.hits.size)
        assertEquals(1, last.confirmed)
        assertEquals(3, last.excluded)
        assertEquals("奥德赛", last.hits.single { it.relevance == SourceRelevance.CONFIRMED }.movie.film.title)
        assertTrue(last.text().contains("原始条目 4"))
    }
    @Test fun originalAliasesNormalizedNamesAndYearTypeConflicts() {
        assertEquals(SourceRelevance.CONFIRMED, HerrinSourceRelevance.classify(target, movie("The Odyssey")))
        assertEquals(SourceRelevance.CONFIRMED, HerrinSourceRelevance.classify(target, movie("Ｔｈｅ Ｏｄｙｓｓｅｙ")))
        assertEquals(SourceRelevance.POSSIBLE, HerrinSourceRelevance.classify(target, movie("奥德赛", "1997")))
        assertEquals(SourceRelevance.POSSIBLE, HerrinSourceRelevance.classify(target, movie("奥德赛", type = "tv")))
        assertEquals(SourceRelevance.POSSIBLE, HerrinSourceRelevance.classify(target, movie("奥德赛：归来")))
        assertEquals(SourceRelevance.CONFIRMED, HerrinSourceRelevance.classify(target,
            movie("另译名").copy(titleAliases = listOf("The Odyssey"))))
    }
    @Test fun newerInvocationPreventsOldProviderPublicationEvenWithoutCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val service = HerrinSourceDiscovery(); val published = mutableListOf<String>()
        fun node(title: String, search: suspend () -> List<HerrinSourceMovie>) =
            HerrinSourceDiscovery.Node(title, title, HerrinSourceType.TVBOX_CMS, search, { error("No details") }, identity = target)
        val old = launch { service.search("old", listOf(node("old") { entered.complete(Unit); release.await(); listOf(movie("后会无期")) }), null, Mutex()) { if(it.hits.isNotEmpty()) published += it.revision } }
        entered.await()
        val fresh = launch(start = CoroutineStart.UNDISPATCHED) { service.search("new", listOf(node("new") { listOf(movie("奥德赛")) }), null, Mutex()) { if(it.hits.isNotEmpty()) published += it.revision } }
        release.complete(Unit); old.join(); fresh.join()
        assertEquals(listOf("new"), published)
    }
    @Test fun stringHeaderAndUaAliasSurviveDirectPlayerResult() {
        val output = SpiderVodCodec.player("""{"url":"https://media.example/authorized.mp4","parse":0,"header":"{\"ua\":\"FixtureAgent\",\"Referer\":\"https://media.example/\"}"}""", false) as PlaybackResult.Ready
        assertEquals("FixtureAgent", output.media.headers["User-Agent"])
        assertEquals("https://media.example/", output.media.headers["Referer"])
    }
    @Test fun openChunkedHlsDoesNotWaitForProbeRangeOrEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setChunkedBody("#EXTM3U\n" + "#comment\n".repeat(200), 8)
                .throttleBody(8, 3, TimeUnit.SECONDS))
            server.start()
            val result = withTimeout(1200) {
                HerrinRemoteMediaProbe(authorizeLocal = { true }).probe(PlaybackMedia(server.url("/live").toString()))
            }
            assertEquals(HerrinRemoteMediaProbe.Result.MEDIA, result)
        }
    }
    @Test fun accurateFailureCodeDoesNotExportPluginMessagesOrUrls() {
        val report = PlaybackErrorReport.source("SPIDER", "MEDIA_PROBE_LOGIN_OR_TEXT：private-url")
        assertTrue(report.contains("SOURCE_MEDIA_PROBE_LOGIN_OR_TEXT"))
        assertFalse(report.contains("private-url"))
        assertFalse(report.contains("UNCLASSIFIED"))
    }
}
