package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PersonalMediaIndexTest {
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
            index.correct(row.getString("id"), 123, "movie", "User title")
            index.record(f, file.copy(size = file.size + 1, modified = "v2"), 2L)
            index.state(folderId, "offline")
            val corrected = index.media(folderId).getJSONObject(0)
            assertEquals("User title", corrected.getString("title"))
            assertEquals(123, corrected.getInt("tmdb")); assertEquals(1, corrected.getInt("manual"))
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
