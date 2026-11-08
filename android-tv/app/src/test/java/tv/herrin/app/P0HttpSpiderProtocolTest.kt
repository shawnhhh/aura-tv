package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class P0HttpSpiderProtocolTest {
    @Test fun typeFourSearchDetailsAndPlayerUseOriginalOpaqueIdsAndExtension() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val site=TvBoxSite("HTTP protocol",false,server.url("/api?token=a%2Fb&ac=stale").toString(),
                key="http",extension="configured-extension",apiType=4)
            val repo=TvBoxVodRepository()
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"local?123","vod_name":"小城良方"}]}"""))
            val found=(repo.search(site,"小城良方",true) as TvBoxVodResult.Ready).films.single()
            val search=server.takeRequest().requestUrl!!
            assertEquals("小城良方",search.queryParameter("wd"))
            assertNull(search.queryParameter("ac"))
            assertEquals("false",search.queryParameter("quick"))
            assertEquals("configured-extension",search.queryParameter("extend"))
            assertTrue(search.encodedQuery!!.contains("token=a%2Fb"))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"local?123","vod_name":"小城良方","vod_play_from":"line-A","vod_play_url":"第一集${'$'}opaque?episode=7"}]}"""))
            val detail=(repo.details(site,found,true) as TvBoxVodDetailResult.Ready).film
            val request=server.takeRequest().requestUrl!!
            assertEquals("local?123",request.queryParameter("ids"))
            assertEquals("detail",request.queryParameter("ac"))
            assertEquals("opaque?episode=7",detail.playbackEpisodes.single().lines.single().uri)
            server.enqueue(MockResponse().setBody("""{"parse":0,"url":"https://example.invalid/video.mp4"}"""))
            assertNotNull(repo.playerContent(site,detail,1,"cms-0",true))
            val play=server.takeRequest().requestUrl!!
            assertEquals("opaque?episode=7",play.queryParameter("play"))
            assertEquals("line-A",play.queryParameter("flag"))
            assertNull(play.queryParameter("ac"))
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun longTypeFourExtensionUsesFormPostLikeFongmi() {
        val site=TvBoxSite("long-ext",false,"https://example.invalid/api?token=a%2Fb",key="http",
            extension="x".repeat(1200),apiType=4)
        val request=TvBoxCmsEndpoint.siteRequest(site,title="小城良方",allowHttp=false)!!
        assertEquals("POST",request.method)
        assertEquals("a/b",request.url.queryParameter("token"))
        assertNull(request.url.queryParameter("extend"))
        val form=request.body as okhttp3.FormBody
        assertEquals("x".repeat(1200),form.value((0 until form.size).first{form.name(it)=="extend"}))
        assertEquals("小城良方",form.value((0 until form.size).first{form.name(it)=="wd"}))
        val player=TvBoxCmsEndpoint.playerRequest(site,"A","opaque",false)!!
        assertEquals("POST",player.method)
    }
    @Test fun ordinaryCmsNeverPassesOpaquePlaybackIdsToMedia3() {
        val json="""{"list":[{"vod_id":"a","vod_name":"Title","vod_play_from":"A","vod_play_url":"集${'$'}opaque"}]}"""
        assertTrue(TvBoxVodCodec.decode(json,true).single().playbackEpisodes.isEmpty())
        assertEquals("opaque",TvBoxVodCodec.decode(json,true,allowOpaque=true).single().playbackEpisodes.single().lines.single().uri)
    }
    @Test fun cmsRoutesKeepTheirOwnParserConfiguration() {
        val site=TvBoxSite("same",false,"https://a.invalid/api",key="a")
        val config=TvBoxConfig.Single(listOf(site),emptyList(),false)
        val store=HerrinTmdbPlaybackRouteStore()
        store.cms("tmdb-tv-1",site,config)
        assertSame(config,(store.forFilm("tmdb-tv-1") as HerrinTmdbPlaybackRouteStore.Route.Cms).config)
    }
    @Test fun xmlCmsSearchAndDetailsPreserveVodAndLine() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val site=TvBoxSite("XML fixture",false,server.url("/xml").toString(),key="xml",apiType=0)
            val listing="<rss><list><video><id>xml-77</id><name>小城良方</name><note>全集</note></video></list></rss>"
            server.enqueue(MockResponse().setHeader("Content-Type","application/xml").setBody(listing))
            val repo=TvBoxVodRepository()
            val hit=(repo.search(site,"小城良方",true) as TvBoxVodResult.Ready).films.single()
            assertEquals("xml-77",hit.cmsVodId)
            assertEquals("videolist",server.takeRequest().requestUrl?.queryParameter("ac"))
            server.enqueue(MockResponse().setBody("<rss><list><video><id>xml-77</id><name>小城良方</name><dl><dd flag=\"A\">第一集${'$'}https://example.invalid/1.mp4</dd></dl></video></list></rss>"))
            val detail=(repo.details(site,hit,true) as TvBoxVodDetailResult.Ready).film
            assertEquals("A",detail.playbackEpisodes.single().lines.single().title)
            assertEquals("xml-77",server.takeRequest().requestUrl?.queryParameter("ids"))
        }
    }
    @Test fun xmlCmsCannotReadExternalEntities() {
        val xml="<!DOCTYPE rss [<!ENTITY ex SYSTEM 'file:///etc/passwd'>]><rss><video><id>1</id><name>&ex;</name></video></rss>"
        assertTrue(runCatching{TvBoxCmsXml.toJson(xml)}.isFailure)
    }

}
