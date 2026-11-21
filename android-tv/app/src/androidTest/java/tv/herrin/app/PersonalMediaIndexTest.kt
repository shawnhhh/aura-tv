package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PersonalMediaIndexTest {
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
}
