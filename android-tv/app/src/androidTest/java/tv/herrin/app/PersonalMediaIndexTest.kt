package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PersonalMediaIndexTest {
    @Test fun existingOfflineFilesAreRegroupedAndRequeuedInPlace() {
        val index = PersonalMediaIndex(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = UUID.randomUUID().toString()
        val folderId = index.folder(connection, "Movies", "auto")
        try {
            val folder = index.folders(connection).single()
            for (name in listOf("2160p.REMUX.mkv", "1080p.WEB-DL.mkv"))
                index.record(folder, PersonalRemoteFile("Movies/流浪地球2（2023）/$name", false, 100, "v1"), 1)
            index.writableDatabase.execSQL(
                "UPDATE media SET title=name,work_key=NULL,identity_revision=0,scrape_next=? WHERE folder=?",
                arrayOf(Long.MAX_VALUE, folderId))
            val ids = (0..1).map { index.media(folderId).getJSONObject(it).getString("id") }.toSet()
            assertTrue(index.dueMetadata(folderId, "", 0L, false).isEmpty())
            assertEquals(2, index.refreshWorkLayout(folder))
            val refreshed = index.dueMetadata(folderId, "", 0L, false)
            assertEquals(ids, refreshed.map { it.getString("id") }.toSet())
            val film = PersonalMediaProjection.Accumulator().apply { add(refreshed) }.browseFilms().single()
            assertEquals("流浪地球2", film.title)
            assertEquals(2, film.playbackEpisodes.single().lines.size)
            assertEquals(0, index.refreshWorkLayout(folder))
        } finally { index.removeFolder(folderId); index.close() }
    }

    @Test fun automaticFolderRefreshDoesNotResetAnIdentifiedAnimeCategory() {
        val index = PersonalMediaIndex(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = UUID.randomUUID().toString()
        val id = index.folder(connection,"Anime","auto")
        try {
            val folder = index.folders(connection).single()
            val file = PersonalRemoteFile("Anime/Example.2024.mp4",false,100,"v1")
            index.record(folder,file,1)
            val mediaId = index.media(id).getJSONObject(0).getString("id")
            index.matched(mediaId,Film("tmdb-movie-42","Example","","动漫","2024","",R.drawable.photofilm_orbit),42,"movie")
            index.folder(connection,"Anime","auto")
            index.record(folder,file,2)
            val saved = index.media(id).getJSONObject(0)
            assertEquals("anime",saved.getString("type"))
            assertEquals(42,saved.getInt("tmdb"))
            assertEquals("movie",org.json.JSONObject(saved.getString("metadata")).getString("identityType"))
            index.correct(mediaId,99,"movie","Different work")
            assertFalse(index.media(id).getJSONObject(0).has("metadata"))
            assertEquals(99,index.media(id).getJSONObject(0).getInt("tmdb"))
        } finally { index.removeFolder(id); index.close() }
    }
    @Test fun scanPersistsUnchangedFilesManualCorrectionsOfflineAndSuccessfulDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = PersonalMediaIndex(context)
        val connection = UUID.randomUUID().toString()
        val folderId = index.folder(connection, "Movies", "movie")
        try {
            val f = index.folders(connection).single()
            val file = PersonalRemoteFile("Movies/Example.2024.mkv", false, 5_000_000_000L, "v1")
            index.record(f, file, 1L); index.finish(folderId, 1L)
            val row = index.media(folderId).getJSONObject(0)
            assertEquals(5_000_000_000L, row.getLong("size"))
            index.correct(row.getString("id"), 123, "movie", "User title", 2025, "https://image.tmdb.org/t/p/w500/fixture.jpg", "User-selected TMDB synopsis")
            index.record(f, file.copy(size = file.size + 1, modified = "v2"), 2L)
            index.state(folderId, "offline")
            val corrected = index.media(folderId).getJSONObject(0)
            assertEquals("User title", corrected.getString("title"))
            assertEquals(2025, corrected.getInt("year"))
            assertEquals("User-selected TMDB synopsis", corrected.getString("synopsis"))
            assertEquals("https://image.tmdb.org/t/p/w500/fixture.jpg", corrected.getString("poster"))
            assertEquals(123, corrected.getInt("tmdb")); assertEquals(1, corrected.getInt("manual"))
            index.folder(connection, "Movies", "tv")
            assertEquals("movie", index.media(folderId).getJSONObject(0).getString("type"))
            assertEquals(123, index.media(folderId).getJSONObject(0).getInt("tmdb"))
            // A partial/failed scan never calls finish and must retain the existing file.
            assertEquals(1, index.media(folderId).length())
            index.finish(folderId, 3L)
            assertEquals(0, index.media(folderId).length())
        } finally { index.removeFolder(folderId); index.close() }
    }
    @Test fun overlappingFoldersAreRejectedAndCredentialsStayOutOfIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = PersonalMediaIndex(context)
        val connection = UUID.randomUUID().toString()
        val id = index.folder(connection, "Movies", "auto")
        try {
            assertThrows(IllegalArgumentException::class.java) { index.folder(connection, "Movies/Sub", "movie") }
            assertThrows(IllegalArgumentException::class.java) { index.folder(connection, "", "movie") }
            index.readableDatabase.rawQuery("PRAGMA table_info(media)", null).use { c ->
                while(c.moveToNext()) assertFalse(c.getString(1) in setOf("password", "username", "authorization", "url", "headers"))
            }
        } finally { index.removeFolder(id); index.close() }
    }
    @Test fun keysetPagesIncludeFilesAfterFiveHundredAndLateDetailsCannotUndoCorrection() {
        val index = PersonalMediaIndex(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = UUID.randomUUID().toString()
        val folderId = index.folder(connection, "Large", "auto")
        try {
            val folder = index.folders(connection).single()
            repeat(507) { index.record(folder, PersonalRemoteFile("Large/Example.$it.mkv",false,100,"v1"),1) }
            val ids = mutableSetOf<String>(); var after = ""
            while (true) {
                val page = index.unmatched(folderId, after)
                if (page.isEmpty()) break
                assertTrue(page.size <= 200)
                page.forEach { assertTrue(ids.add(it.getString("id"))) }
                after = page.last().getString("id")
                index.scrapeCursor(folderId, after)
                assertEquals(after,index.scrapeCursor(folderId))
            }
            assertEquals(507,ids.size)
            // This test shares the real database with other suites; filter this folder.
            var allAfter = ""; val visible = mutableSetOf<String>()
            do {
                val page = index.mediaAfter(allAfter)
                page.filter { it.getString("folder") == folderId }.forEach { visible.add(it.getString("id")) }
                allAfter = page.lastOrNull()?.getString("id").orEmpty()
            } while (allAfter.isNotEmpty())
            assertEquals(ids,visible)
            val id = ids.first()
            index.correct(id,42,"movie","My title",2024)
            index.correct(id,99,"tv","New selection",2025)
            val stale = Film("tmdb-movie-42","Old title","","电影","2024","Old synopsis",R.drawable.photofilm_orbit)
            assertFalse(index.enrichCorrection(id,42,"movie",stale))
            assertEquals("New selection",index.find(id)!!.getString("title"))
            val current = stale.copy(id="tmdb-tv-99",actors=listOf("Actor"),backdropUrl="https://image.tmdb.org/t/p/original/example.jpg")
            assertTrue(index.enrichCorrection(id,99,"tv",current))
            val saved = index.find(id)!!
            assertEquals("New selection",saved.getString("title")); assertEquals(1,saved.getInt("manual"))
            assertEquals("Actor",org.json.JSONObject(saved.getString("metadata")).getJSONArray("actors").getString(0))
            index.correct(id,77,"movie","Confirmed animation",2024)
            assertTrue(index.enrichCorrection(id,77,"movie",current.copy(id="tmdb-movie-77",category="动漫")))
            val animation = index.find(id)!!
            assertEquals("anime",animation.getString("type"))
            assertEquals("movie",org.json.JSONObject(animation.getString("metadata")).getString("identityType"))
            assertEquals("动漫",PersonalMediaProjection.films(listOf(animation)).single().category)
        } finally { index.removeFolder(folderId); index.close() }
    }
    @Test fun traditionalTitlesMatchConservativelyWithoutMergingDifferentYearsOrSequels() {
        if(android.os.Build.VERSION.SDK_INT < 29) return
        assertEquals("进击的巨人 II",PersonalMediaMatching.canonical("進擊的巨人 Ⅱ"))
        val discovery = HerrinTmdbDiscovery(ArtworkJsonFetcher { _, _ -> null })
        val film = Film("tmdb-tv-42","进击的巨人 II","","动漫","2024","",R.drawable.photofilm_orbit)
        val identity = HerrinFilmIdentity(1,"tv",listOf("進擊的巨人 Ⅱ"),2024)
        assertEquals(film,PersonalMediaMatching.choose(identity,listOf(film),discovery))
        assertNull(PersonalMediaMatching.choose(identity,listOf(film.copy(year="2023")),discovery))
        assertNull(PersonalMediaMatching.choose(identity.copy(names=listOf("進擊的巨人 III")),listOf(film),discovery))
    }
}
