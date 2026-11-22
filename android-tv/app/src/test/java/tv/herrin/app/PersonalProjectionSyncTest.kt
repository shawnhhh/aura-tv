package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalProjectionSyncTest {
    @Test fun animatedMovieAndSeriesWithSameTmdbNumberCannotBecomeVersionsOfOneWork() {
        fun row(id: String, type: String) = JSONObject().put("id",id).put("name","$id.mkv")
            .put("title",id).put("tmdb",42).put("type","anime")
            .put("metadata",JSONObject().put("identityType",type).toString())
        val films = PersonalMediaProjection.films(listOf(row("film","movie"),row("series","tv")))
        assertEquals(2, films.size)
        assertEquals(setOf("personal-index:film","personal-index:series"),films.map { it.id }.toSet())
        assertTrue(films.all { it.playbackEpisodes.single().lines.size == 1 })
    }
    @Test fun versionsShareOneFilmButKeepEveryOriginalFileIdAndEpisode() {
        fun row(id: String, episode: Int) = JSONObject().put("id", id).put("name", "$id.mkv")
            .put("title", "Example").put("tmdb", 42).put("type", "tv").put("season", 2).put("episode", episode)
        val film = PersonalMediaProjection.films(listOf(row("file-a",1),row("file-b",1),row("file-c",2))).single()
        assertEquals(2, film.playbackEpisodes.size)
        assertEquals(setOf("personal-file:file-a", "personal-file:file-b"), film.playbackEpisodes.first().lines.map { it.id }.toSet())
        assertEquals("personal-index:file-c", film.playbackEpisodes.last().lines.single().uri)
        assertEquals(2, PersonalMediaProjection.films(listOf(row("a",1),row("b",1).put("season",3))).size)
    }
    @Test fun unrecognizedFilesAreNeverMergedBySimilarFilename() {
        val rows = listOf("a","b").map { JSONObject().put("id",it).put("name","Unknown").put("title","Unknown").put("type","video") }
        assertEquals(2,PersonalMediaProjection.films(rows).size)
    }
    @Test fun onlyDueFoldersNeedNetworkAndOfflineClockChangesCannotStrandSync() {
        assertTrue(PersonalSyncPolicy.due(0,100))
        assertFalse(PersonalSyncPolicy.due(100,101))
        assertTrue(PersonalSyncPolicy.due(100,100 + PersonalSyncPolicy.INTERVAL_MS))
        assertTrue(PersonalSyncPolicy.due(100,99))
    }
    @Test fun parentAndSeasonCanIdentifyBareEpisodeFilesWithoutRenaming() {
        val claim = PersonalStoragePolicy.parse("S02E03.1080p.mkv", "Shows/Example Show (2024)/Season 2")
        assertEquals("Example Show",claim.title)
        assertEquals(2024,claim.year)
        assertEquals(2,claim.season)
        assertEquals(3,claim.episode)
    }
    @Test fun pagedProjectionMergesVersionsAcrossBoundariesAndRetainsDifferentSeasons() {
        fun row(id: String, season: Int, episode: Int) = JSONObject().put("id",id).put("name","$id.mkv")
            .put("title","Example").put("type","tv").put("tmdb",42).put("season",season).put("episode",episode)
        val pages = listOf(listOf(row("a",1,1),row("b",2,1)),listOf(row("c",1,1),row("d",1,2)))
        val projection = PersonalMediaProjection.Accumulator()
        pages.forEach(projection::add)
        val films = projection.films()
        assertEquals(2,films.size)
        val seasonOne = films.single { it.id == "personal-index:a" }
        assertEquals(listOf(1,2),seasonOne.playbackEpisodes.map { it.number })
        assertEquals(listOf("personal-file:a","personal-file:c"),seasonOne.playbackEpisodes.first().lines.map { it.id })
        assertEquals("personal-index:d",seasonOne.playbackEpisodes.last().lines.single().uri)
        assertEquals("personal-index:b",films.single { it.id != seasonOne.id }.id)
    }
}
