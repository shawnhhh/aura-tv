package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class P0RecentIndexTest {
    @Test fun coldRecentIndexUsesOnlyPublicMetadataAndRejectsRetiredWriters() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val h=PlaybackHistory(context)
        val id="index-${java.util.UUID.randomUUID()}"
        val f=Film(id,"Original Series","","剧集","2026","Original synopsis",1,
            artworkUrl="https://example.test/poster.jpg",backdropUrl="https://example.test/b.jpg?token=SECRET",
            playbackEpisodes=listOf(PlaybackEpisode(5,"S02E03",listOf(PlaybackLine("approved","Original","https://private.test/movie?token=SECRET")))))
        val scope="private-scope-SECRET"
        h.update(scope,id,5,"approved",10000,100000)
        assertEquals(1,h.importLegacy(scope,listOf(f)))
        assertEquals(0,h.importLegacy(scope,listOf(f)))
        assertEquals(10000L,PlaybackHistory(context).indexedProgress(id)!!.positionMs)
        val old=h.begin(scope,id,5,"approved")
        val current=h.begin(scope,id,5,"approved")
        h.updateSession(current,scope,id,5,"approved",30000,100000,false)
        h.recordFilm(current,scope,f,5,"approved",30000,100000)
        h.recordFilm(old,scope,f,5,"approved",90000,100000)
        val restored=PlaybackHistory(context)
        val item=restored.indexedFilms().first { it.id==id }
        assertEquals("Original Series",item.title);assertTrue(item.subtitle.contains("30%"))
        assertTrue(item.playbackEpisodes.isEmpty());assertNull(item.backdropUrl)
        assertEquals(0.3f,restored.indexedProgress(id)!!.fraction,0.001f)
        assertEquals(30000L,restored.resolveIndexed(f,"new-approved-scope")!!.second)
        val missing=f.copy(playbackEpisodes=listOf(PlaybackEpisode(1,"S02E04",f.playbackEpisodes[0].lines)))
        assertNull(restored.resolveIndexed(missing,scope))
        assertFalse(context.getSharedPreferences("herrin-watch-history",0).getString("recent-index","")!!.contains("SECRET"))
        val metadata=java.io.File(context.filesDir,"tvbox-home-snapshot").listFiles().orEmpty().filter { it.readText().contains(id) }.single().readText()
        assertFalse(metadata.contains("SECRET"));assertFalse(metadata.contains("private.test"))
        h.updateSession(current,scope,id,5,"approved",100000,100000,true)
        h.recordFilm(current,scope,f,5,"approved",100000,100000)
        assertTrue(PlaybackHistory(context).indexedFilms().first { it.id==id }.subtitle.contains("已看完"))
        assertEquals(0L,PlaybackHistory(context).resolveIndexed(f,scope)!!.second)
    }
}
