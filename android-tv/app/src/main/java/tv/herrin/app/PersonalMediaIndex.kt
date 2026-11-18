package tv.herrin.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** No credentials, request headers or authorized URLs enter this database. */
internal class PersonalMediaIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "personal-media.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY, connection TEXT NOT NULL, path TEXT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL DEFAULT 'pending', scanned INTEGER NOT NULL DEFAULT 0, UNIQUE(connection,path))")
        db.execSQL("CREATE TABLE media(id TEXT PRIMARY KEY, connection TEXT NOT NULL, folder TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified TEXT NOT NULL, extension TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, year INTEGER, season INTEGER, episode INTEGER, tmdb INTEGER, confidence REAL NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'available', scanned INTEGER NOT NULL, synopsis TEXT, poster TEXT)")
        db.execSQL("CREATE INDEX media_folder ON media(folder,status)")
        db.execSQL("CREATE INDEX media_tmdb ON media(tmdb,type,status)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    data class Folder(val id: String, val connection: String, val path: String, val type: String, val enabled: Boolean, val state: String, val scanned: Long)
    fun folders(connection: String? = null): List<Folder> = readableDatabase.query("folders", null,
        connection?.let { "connection=?" }, connection?.let { arrayOf(it) }, null, null, "path").use { c ->
        buildList { while(c.moveToNext()) add(Folder(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0, c.getString(5), c.getLong(6))) }
    }
    @Synchronized fun folder(connection: String, raw: String, type: String, enabled: Boolean = true): String {
        val path = PersonalStoragePolicy.path(raw)
        require(type in PersonalStoragePolicy.mediaTypes)
        val current = folders(connection)
        val old = current.firstOrNull { it.path == path }
        require(current.none { it.id != old?.id && (PersonalStoragePolicy.contains(it.path, path) || PersonalStoragePolicy.contains(path, it.path)) }) { "OVERLAPPING_FOLDER" }
        val id = old?.id ?: UUID.randomUUID().toString()
        val values = ContentValues().apply { put("id", id); put("connection", connection); put("path", path); put("type", type); put("enabled", if(enabled) 1 else 0) }
        if(old == null) writableDatabase.insertOrThrow("folders", null, values)
        else writableDatabase.update("folders", values, "id=?", arrayOf(id))
        if(old != null && old.type != type) writableDatabase.execSQL("UPDATE media SET tmdb=NULL,confidence=0,synopsis=NULL,poster=NULL WHERE folder=? AND manual=0", arrayOf(id))
        writableDatabase.execSQL("UPDATE media SET type=CASE WHEN ?='auto' THEN CASE WHEN episode IS NULL THEN 'movie' ELSE 'tv' END ELSE ? END WHERE folder=? AND manual=0", arrayOf(type, type, id))
        return id
    }
    fun removeFolder(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("media", "folder=?", arrayOf(id))
            writableDatabase.delete("folders", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun state(id: String, state: String) { writableDatabase.execSQL("UPDATE folders SET state=? WHERE id=?", arrayOf(state, id)) }
    fun record(f: Folder, file: PersonalRemoteFile, scan: Long) {
        writableDatabase.beginTransaction()
        try {
            recordInTransaction(f, file, scan)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    private fun recordInTransaction(f: Folder, file: PersonalRemoteFile, scan: Long) {
        val id = SourceHealthIndex.digest(f.connection + "\u0000" + file.path)
        // Unchanged files keep all scraped metadata and explicit manual corrections.
        val changed = writableDatabase.compileStatement("UPDATE media SET scanned=?,status='available' WHERE id=? AND size=? AND modified=?").use {
            it.bindLong(1, scan); it.bindString(2, id); it.bindLong(3, file.size); it.bindString(4, file.modified); it.executeUpdateDelete()
        }
        if(changed > 0) return
        val parsed = PersonalStoragePolicy.parse(file.name)
        val values = ContentValues().apply {
            put("id", id); put("connection", f.connection); put("folder", f.id); put("path", file.path); put("name", file.name)
            put("size", file.size); put("modified", file.modified); put("extension", file.name.substringAfterLast('.').lowercase())
            put("type", if(f.type == "auto") if(parsed.episode == null) "movie" else "tv" else f.type)
            put("title", parsed.title); put("year", parsed.year); put("season", parsed.season); put("episode", parsed.episode)
            put("scanned", scan); put("status", "available")
        }
        if(rows("SELECT manual FROM media WHERE id=?", arrayOf(id)).firstOrNull()?.optInt("manual") == 1) {
            values.remove("title"); values.remove("year"); values.remove("type")
        } else { values.putNull("tmdb"); values.put("confidence", 0); values.putNull("synopsis"); values.putNull("poster") }
        if(writableDatabase.update("media", values, "id=?", arrayOf(id)) == 0) writableDatabase.insertOrThrow("media", null, values)
    }
    fun finish(id: String, scan: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE media SET status='missing' WHERE folder=? AND scanned<>?", arrayOf(id, scan))
            writableDatabase.execSQL("UPDATE folders SET state='complete',scanned=? WHERE id=?", arrayOf(scan, id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun media(folder: String? = null, limit: Int = 500, offset: Int = 0, category: String = "all"): JSONArray {
        require(category in setOf("all", "movie", "tv", "unmatched"))
        val filter = when(category) { "unmatched" -> " AND tmdb IS NULL"; "movie" -> " AND type='movie'"; "tv" -> " AND type IN ('tv','anime')"; else -> "" }
        val where = "status='available'" + (if(folder != null) " AND folder=?" else "") + filter
        return readableDatabase.query("media", null, where, folder?.let { arrayOf(it) }, null, null, "title,path",
            "${offset.coerceIn(0, 10000000)},${limit.coerceIn(1, 500)}").use { c ->
            JSONArray().apply { while(c.moveToNext()) put(JSONObject().apply { c.columnNames.forEachIndexed { i, key -> if(!c.isNull(i)) put(key, c.getString(i)) } }) }
        }
    }
    private fun rows(sql: String, args: Array<String>): List<JSONObject> = readableDatabase.rawQuery(sql, args).use { c ->
        buildList { while(c.moveToNext()) add(JSONObject().apply { c.columnNames.forEachIndexed { i, key -> if(!c.isNull(i)) put(key, c.getString(i)) } }) }
    }
    fun find(id: String): JSONObject? = rows("SELECT * FROM media WHERE id=? AND status='available'", arrayOf(id)).firstOrNull()
    fun candidates(connection: String, identity: HerrinFilmIdentity): List<JSONObject> {
        val names = identity.names.take(8).map { "%" + it.replace("%", "\\%").replace("_", "\\_") + "%" }
        val clause = names.joinToString(" OR ") { "title LIKE ? ESCAPE '\\'" }
        return rows("SELECT * FROM media WHERE connection=? AND status='available' AND (tmdb=? OR ($clause)) LIMIT 200",
            (listOf(connection, identity.tmdbId.toString()) + names).toTypedArray())
    }
    fun unmatched(folder: String): List<JSONObject> = rows("SELECT * FROM media WHERE folder=? AND status='available' AND tmdb IS NULL AND manual=0 LIMIT 200", arrayOf(folder))
    fun matched(id: String, film: Film, tmdb: Int, type: String) {
        writableDatabase.execSQL("UPDATE media SET tmdb=?,type=?,title=?,year=?,synopsis=?,poster=?,confidence=0.95 WHERE id=? AND manual=0",
            arrayOf(tmdb, type, film.title, film.year, film.synopsis, film.artworkUrl, id))
    }
    companion object {
        @Volatile private var shared: PersonalMediaIndex? = null
        fun shared(context: Context): PersonalMediaIndex = shared ?: synchronized(this) {
            shared ?: PersonalMediaIndex(context).also { shared = it }
        }
    }
    fun correct(id: String, tmdb: Int, type: String, title: String, year: Int? = null,
        poster: String = "", synopsis: String = "") {
        require(tmdb > 0 && type in setOf("movie", "tv") && title.length in 1..256)
        require(year == null || year in 1800..2200)
        require(poster.isEmpty() || Regex("^https://image\\.tmdb\\.org/t/p/(?:w500|original)/[A-Za-z0-9_.-]{1,140}\\.(?:jpg|jpeg|png|webp)$").matches(poster))
        val values = ContentValues().apply {
            put("tmdb", tmdb); put("type", type); put("title", title); put("manual", 1); put("confidence", 1)
            if(year != null) put("year", year)
            if(poster.isEmpty()) putNull("poster") else put("poster", poster)
            put("synopsis", synopsis.take(2000))
        }
        writableDatabase.update("media", values, "id=?", arrayOf(id))
    }
}
