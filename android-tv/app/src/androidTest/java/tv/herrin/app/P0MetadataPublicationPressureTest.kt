package tv.herrin.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Controlled API responses through the production probe/discovery/cache/publication effects. */
class P0MetadataPublicationPressureTest {
    private val token="SyntheticReadAccessForPressure".repeat(4)
    private val requests=AtomicInteger()
    private val offline=AtomicBoolean()
    @get:Rule(order=0) val setup=object:ExternalResource(){
        override fun before(){
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively()
            Preferences(c).tmdbReadToken=token
            MetadataFetchers.overrideForTests=ArtworkJsonFetcher { url,_ ->
                requests.incrementAndGet()
                if(offline.get()) throw java.net.UnknownHostException()
                val uri=URI(url)
                when {
                    uri.path.endsWith("configuration") -> "{\"images\":{}}"
                    uri.path.contains("search/") -> "{\"results\":[]}"
                    url.contains("append_to_response") -> {Thread.sleep(70);"{broken-detail"}
                    else -> {
                        Thread.sleep(8)
                        val page=Regex("(?:[?&])page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt()?:1
                        val base=if(uri.path.contains("top_rated")) 1000 else
                            ((uri.path+uri.query.orEmpty().substringBefore("&page=")).hashCode().toLong().let{kotlin.math.abs(it)}%10000).toInt()*1000+2000
                        val array=JSONArray()
                        repeat(20){index->
                            val id=base+(page-1)*20+index+1
                            array.put(JSONObject().put("id",id).put("title","Original $id").put("name","Original $id")
                                .put("genre_ids",JSONArray().put(18)).put("release_date","2026-01-01").put("first_air_date","2026-01-01")
                                .put("poster_path", if(index%2==0) "https://invalid.invalid/private" else JSONObject.NULL))
                            if(index==1 && !uri.path.contains("top_rated")) array.put(JSONObject(array.getJSONObject(array.length()-1).toString())) // duplicate ID, still 20 unique
                        }
                        JSONObject().put("results",array).toString()
                    }
                }
            }
        }
        override fun after(){
            MetadataFetchers.overrideForTests=null
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("herrin",0).edit().clear().commit()
            java.io.File(c.filesDir,"tmdb-home-pages").deleteRecursively()
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun ready(){compose.waitUntil(15000){runCatching{compose.onNodeWithTag("splash-curtain").assertDoesNotExist()}.isSuccess}}
    private fun cache()=HerrinTmdbDiscovery(cacheDir=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"tmdb-home-pages"))
    @Test fun validProbePublishesHundredsThenTop250WhileCategoriesAndRemoteRemainUsable(){
        ready()
        compose.waitUntil(15000){cache().cachedHome(token).size>=100}
        for(cat in listOf("电影","剧集","动漫","Top 250","首页","电影","Top 250")){
            compose.onNodeWithTag("nav-$cat").performClick()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        }
        compose.onNodeWithTag("nav-Top 250").performClick()
        compose.waitUntil(15000){cache().cachedTop250(token).size==250}
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("catalog-film-rail").performScrollToIndex(249)}.isSuccess}
        compose.onNodeWithTag("card-tmdb-movie-1250").assertExists()
        assertFalse(compose.activity.isFinishing)
        assertTrue("Requests must stay bounded, including cancelled/restarted pages",requests.get()<70)
    }
    @Test fun malformedDetailAndActivityRecreationRetainCachedHomeDuringNetworkLoss(){
        ready();compose.waitUntil(15000){cache().cachedHome(token).size>=100}
        compose.onNodeWithTag("nav-电影").performClick()
        compose.onNodeWithTag("catalog-film-rail").performScrollToIndex(0)
        val posters=compose.onAllNodes(SemanticsMatcher("source card"){it.config.getOrElse(SemanticsProperties.TestTag){""}.startsWith("card-tmdb-movie-")})
        val previousSearch=SourceSearchDiagnostics.currentStart()
        posters.onFirst().performClick()
        compose.onNodeWithTag("film-detail-list").assertExists()
        compose.onNodeWithTag("tmdb-source-lookup-status").assertIsDisplayed()
        compose.waitUntil(3000){SourceSearchDiagnostics.currentStart()!=previousSearch &&SourceSearchDiagnostics.firstFeedbackMs()>=0}
        android.util.Log.i("HerrinSourceFeedbackTest","controlled_metadata_no_private_provider_first_status_cpu_draw_ms=${SourceSearchDiagnostics.firstFeedbackMs()} physical_tcl=0")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle();compose.onNodeWithTag("exit-dialog").assertDoesNotExist()
        offline.set(true)
        compose.activityRule.scenario.recreate();ready()
        compose.onNodeWithTag("nav-电影").assertExists()
        assertFalse(compose.activity.isFinishing)
        assertTrue(cache().cachedHome(token).isNotEmpty())
    }
}
