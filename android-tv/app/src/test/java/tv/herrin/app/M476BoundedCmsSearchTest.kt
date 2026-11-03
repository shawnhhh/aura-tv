package tv.herrin.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class M476BoundedCmsSearchTest {
    private val identity = HerrinFilmIdentity(157336, "movie",
        listOf("Interstellar"), 2014)
    private fun offer(name: String) = HerrinSourceMovie(
        name, HerrinSourceType.TVBOX_CMS,
        Film("film-$name", "Interstellar", "", "电影", "2014", "",
            R.drawable.photofilm_orbit), "movie")

    @Test fun cmsQueriesRunConcurrentlyButNeverStartTheActiveSpiderInParallel() = runBlocking {
        val concurrent = AtomicInteger()
        val highest = AtomicInteger()
        val calls = mutableListOf<String>()
        val sources = (1..3).map { index ->
            val name = "cms-$index"
            object : HerrinFilmSource {
                override val id = name
                override val type = HerrinSourceType.TVBOX_CMS
                override suspend fun search(identity: HerrinFilmIdentity):
                    List<HerrinSourceMovie> {
                    val now = concurrent.incrementAndGet()
                    highest.updateAndGet { maxOf(it, now) }
                    delay(120)
                    concurrent.decrementAndGet()
                    synchronized(calls) { calls += id }
                    return listOf(offer(id))
                }
            }
        } + listOf(object : HerrinFilmSource {
            override val id = "spider-last"
            override val type = HerrinSourceType.TVBOX_SPIDER
            override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> {
                assertEquals(0, concurrent.get())
                synchronized(calls) { calls += id }
                return emptyList()
            }
        })
        val result = HerrinFilmRouter(sources).find(identity)
        assertEquals(3, result.options.size)
        assertTrue("Independent CMS endpoints should not block each other serially",
            highest.get() >= 2)
        assertEquals(0, concurrent.get())
        assertEquals(listOf("cms-1", "cms-2", "cms-3", "spider-last"),
            result.searchedSources)
        assertEquals("spider-last", calls.last())
    }

    @Test fun oneFailedCmsDoesNotPreventOtherAuthorizedCmsResults() = runBlocking {
        val calls = AtomicInteger()
        val sources = (1..4).map { index ->
            object : HerrinFilmSource {
                override val id = "cms-$index"
                override val type = HerrinSourceType.TVBOX_CMS
                override suspend fun search(identity: HerrinFilmIdentity):
                    List<HerrinSourceMovie> {
                    calls.incrementAndGet()
                    if (index == 2) error("Provider unavailable")
                    return listOf(offer(id))
                }
            }
        }
        val result = HerrinFilmRouter(sources).find(identity)
        assertEquals(4, calls.get())
        assertEquals(3, result.options.size)
        assertEquals("来源检索失败", result.errors["cms-2"])
        assertEquals(HerrinSourceTier.TVBOX, result.selectedTier)
    }
}
