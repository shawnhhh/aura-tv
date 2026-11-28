package tv.herrin.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** No credentials, request headers or authorized URLs enter this database. */
internal class PersonalMediaIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "personal-media.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY, connection TEXT NOT NULL, path TEXT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL DEFAULT 'pending', scanned INTEGER NOT NULL DEFAULT 0, UNIQUE(connection,path))")
        db.execSQL("CREATE TABLE media(id TEXT PRIMARY KEY, connection TEXT NOT NULL, folder TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified TEXT NOT NULL, extension TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, year INTEGER, season INTEGER, episode INTEGER, tmdb INTEGER, confidence REAL NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'available', scanned INTEGER NOT NULL, synopsis TEXT, poster TEXT)")
        db.execSQL("CREATE INDEX media_folder ON media(folder,status)")
        db.execSQL("CREATE INDEX media_tmdb ON media(tmdb,type,status)")
        addMetadataColumns(db)
        addScrapeCursor(db)
        addMetadataQueue(db)
        addWorkLayout(db)
    }
    private fun addMetadataColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN metadata TEXT")
        db.execSQL("ALTER TABLE media ADD COLUMN scrape_code TEXT")
    }
    private fun addScrapeCursor(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE folders ADD COLUMN scrape_after TEXT NOT NULL DEFAULT ''")
    }
    private fun addMetadataQueue(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN scrape_next INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE folders ADD COLUMN nfo_after TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX media_scrape_queue ON media(folder,status,id,scrape_next)")
    }
    private fun addWorkLayout(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN work_key TEXT")
        db.execSQL("ALTER TABLE media ADD COLUMN identity_revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX media_work ON media(folder,work_key,tmdb,status)")
        db.execSQL("UPDATE folders SET scrape_after=''")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addMetadataColumns(db)
        if (oldVersion < 3) addScrapeCursor(db)
        if (oldVersion < 4) addMetadataQueue(db)
        if (oldVersion < 5) addWorkLayout(db)
    }
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
        if(old != null && old.type != type) writableDatabase.execSQL("UPDATE media SET tmdb=NULL,confidence=0,synopsis=NULL,poster=NULL,metadata=NULL,scrape_code=NULL,scrape_next=0 WHERE folder=? AND manual=0", arrayOf(id))
        writableDatabase.execSQL("UPDATE media SET type=CASE WHEN ?='auto' THEN CASE WHEN episode IS NULL THEN 'movie' ELSE 'tv' END ELSE ? END WHERE folder=? AND manual=0 AND (tmdb IS NULL OR ?<>'auto')", arrayOf(type, type, id, type))
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
        val parsed = PersonalStoragePolicy.parse(file.name, file.path.substringBeforeLast('/', ""))
        val type = if(f.type == "auto") if(parsed.episode == null) "movie" else "tv" else f.type
        if(changed > 0) {
            // Old unmatched indexes need the improved parent/season parser
            // even when the remote file itself did not change. Proven matches
            // and manual corrections keep all of their existing metadata.
            writableDatabase.execSQL(
                """UPDATE media SET title=?,year=?,season=?,episode=?,type=?,work_key=?,
                    scrape_next=CASE WHEN identity_revision=0 THEN 0 ELSE scrape_next END,identity_revision=1
                    WHERE id=? AND tmdb IS NULL AND manual=0""",
                arrayOf(parsed.title, parsed.year, parsed.season, parsed.episode, type, parsed.workKey(type), id))
            return
        }
        val values = ContentValues().apply {
            put("id", id); put("connection", f.connection); put("folder", f.id); put("path", file.path); put("name", file.name)
            put("size", file.size); put("modified", file.modified); put("extension", file.name.substringAfterLast('.').lowercase())
            put("type", type)
            put("title", parsed.title); put("year", parsed.year); put("season", parsed.season); put("episode", parsed.episode)
            put("work_key", parsed.workKey(type)); put("identity_revision", 1)
            put("scanned", scan); put("status", "available")
        }
        if(rows("SELECT manual FROM media WHERE id=?", arrayOf(id)).firstOrNull()?.optInt("manual") == 1) {
            values.remove("title"); values.remove("year"); values.remove("type")
            values.remove("season"); values.remove("episode"); values.remove("work_key")
        } else { values.putNull("tmdb"); values.put("confidence", 0); values.putNull("synopsis"); values.putNull("poster"); values.putNull("metadata"); values.putNull("scrape_code"); values.put("scrape_next", 0L) }
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
    fun unmatched(folder: String, after: String = ""): List<JSONObject> = rows(
        "SELECT * FROM media WHERE folder=? AND id>? AND status='available' AND tmdb IS NULL AND manual=0 ORDER BY id LIMIT 200", arrayOf(folder, after))
    fun scrapeCursor(folder: String): String = rows("SELECT scrape_after FROM folders WHERE id=?", arrayOf(folder))
        .firstOrNull()?.optString("scrape_after").orEmpty()
    fun scrapeCursor(folder: String, after: String) {
        writableDatabase.execSQL("UPDATE folders SET scrape_after=? WHERE id=?", arrayOf(after, folder))
    }
    fun pendingCorrections(folder: String, after: String = ""): List<JSONObject> = rows(
        "SELECT * FROM media WHERE folder=? AND id>? AND status='available' AND manual=1 AND tmdb>0 AND scrape_code='MANUAL_MATCH' ORDER BY id LIMIT 200", arrayOf(folder, after))
    fun dueMetadata(folder: String, after: String, at: Long, manualOnly: Boolean): List<JSONObject> = rows(
        """SELECT * FROM media WHERE folder=? AND id>? AND status='available' AND scrape_next<=?
            AND ((tmdb IS NULL AND manual=0) OR scrape_code IN ('MANUAL_MATCH','DETAILS_PENDING'))
            AND (CAST(? AS INTEGER)=0 OR manual=1) ORDER BY id LIMIT 32""", arrayOf(folder, after, at.toString(), if (manualOnly) "1" else "0"))

    /** Reparse indexed paths in bounded pages after upgrade, including when a share is offline. */
    fun refreshWorkLayout(folder: Folder): Int {
        val page = rows(
            "SELECT id,name,path,tmdb,season,episode FROM media WHERE folder=? AND status='available' AND manual=0 AND identity_revision=0 ORDER BY id LIMIT 200",
            arrayOf(folder.id))
        if (page.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in page) {
                val parsed = PersonalStoragePolicy.parse(row.getString("name"), row.getString("path").substringBeforeLast('/', ""))
                val type = if (folder.type == "auto") if (parsed.episode == null) "movie" else "tv" else folder.type
                val values = ContentValues().apply {
                    put("identity_revision", 1)
                    put("work_key", parsed.workKey(type))
                    if (row.optInt("tmdb") <= 0) {
                        put("title", parsed.title); put("year", parsed.year); put("type", type)
                        put("season", parsed.season); put("episode", parsed.episode)
                        put("scrape_next", 0L)
                    } else if (!row.has("episode") && parsed.episode != null) {
                        put("season", parsed.season); put("episode", parsed.episode)
                    }
                }
                db.update("media", values, "id=? AND manual=0 AND identity_revision=0", arrayOf(row.getString("id")))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return page.size
    }

    fun reuseWorkMetadata(row: JSONObject): Boolean {
        val key = row.optString("work_key").takeIf(String::isNotBlank) ?: return false
        val peers = rows(
            """SELECT tmdb,type FROM media WHERE folder=? AND work_key=? AND tmdb>0 AND status='available'
                GROUP BY tmdb,type LIMIT 2""", arrayOf(row.getString("folder"), key))
        if (peers.size != 1) return false
        val peer = rows(
            """SELECT tmdb,type,title,year,synopsis,poster,metadata FROM media
                WHERE folder=? AND work_key=? AND tmdb>0 AND status='available' AND scrape_code='MATCHED' LIMIT 1""",
            arrayOf(row.getString("folder"), key)).singleOrNull() ?: return false
        val values = ContentValues().apply {
            for (name in listOf("tmdb", "type", "title", "year", "synopsis", "poster", "metadata"))
                peer.optString(name).takeIf(String::isNotBlank)?.let { put(name, it) }
            put("confidence", 0.95); put("scrape_code", "MATCHED"); put("scrape_next", 0L)
        }
        return writableDatabase.update("media", values,
            "id=? AND size=? AND modified=? AND status='available' AND manual=0 AND tmdb IS NULL AND work_key=?",
            arrayOf(row.getString("id"), row.getString("size"), row.getString("modified"), key)) > 0
    }

    fun delayMetadata(row: JSONObject, code: String, retryAt: Long) {
        writableDatabase.execSQL(
            "UPDATE media SET scrape_code=?,scrape_next=? WHERE id=? AND size=? AND modified=? AND status='available' AND manual=? AND COALESCE(tmdb,0)=CAST(? AS INTEGER)",
            arrayOf(code, retryAt, row.getString("id"), row.getLong("size"), row.getString("modified"),
                row.optInt("manual"), row.optInt("tmdb")))
    }

    fun nfoCursor(folder: String): String =
        rows("SELECT nfo_after FROM folders WHERE id=?", arrayOf(folder)).firstOrNull()?.optString("nfo_after").orEmpty()

    fun nfoCursor(folder: String, after: String) {
        writableDatabase.execSQL("UPDATE folders SET nfo_after=? WHERE id=?", arrayOf(after, folder))
    }

    /** Preserve confirmed identity and available artwork while official details are pending. */
    fun acceptMatch(row: JSONObject, film: Film, tmdb: Int, type: String, detailsPending: Boolean, retryAt: Long): Boolean {
        val category = when (film.category) { "动漫" -> "anime"; "纪录片" -> "documentary"; else -> type }
        val values = ContentValues().apply {
            put("tmdb", tmdb); put("type", category); put("title", film.title); put("year", film.year)
            if (film.synopsis.isNotBlank()) put("synopsis", film.synopsis)
            film.artworkUrl?.let { put("poster", it) }
            put("metadata", PersonalMediaProjection.metadata(film, type).toString())
            put("scrape_code", if (detailsPending) "DETAILS_PENDING" else "MATCHED")
            put("scrape_next", if (detailsPending) retryAt else 0L)
            put("confidence", 0.95)
        }
        return writableDatabase.update("media", values,
            "id=? AND size=? AND modified=? AND status='available' AND manual=0 AND COALESCE(tmdb,0)=CAST(? AS INTEGER)",
            arrayOf(row.getString("id"), row.getString("size"), row.getString("modified"), row.optInt("tmdb").toString())) > 0
    }

    fun missingToken(folder: String) {
        writableDatabase.execSQL("UPDATE media SET scrape_code='TOKEN_MISSING' WHERE folder=? AND status='available' AND tmdb IS NULL AND manual=0", arrayOf(folder))
    }
    /** A late detail response must never overwrite a newer manual correction. */
    fun enrichCorrection(id: String, tmdb: Int, type: String, film: Film): Boolean {
        val category = when(film.category) { "动漫" -> "anime"; "纪录片" -> "documentary"; else -> type }
        return writableDatabase.compileStatement("UPDATE media SET metadata=?,type=?,poster=CASE WHEN poster IS NULL OR poster='' THEN ? ELSE poster END,synopsis=CASE WHEN synopsis IS NULL OR synopsis='' THEN ? ELSE synopsis END,scrape_code='MANUAL_COMPLETE' WHERE id=? AND tmdb=? AND type=? AND manual=1 AND scrape_code='MANUAL_MATCH'").use {
            it.bindString(1, PersonalMediaProjection.metadata(film, type).toString()); it.bindString(2,category)
            it.bindString(3,film.artworkUrl.orEmpty()); it.bindString(4,film.synopsis); it.bindString(5,id)
            it.bindLong(6,tmdb.toLong()); it.bindString(7,type); it.executeUpdateDelete() > 0
        }
    }
    fun mediaAfter(after: String = ""): List<JSONObject> = rows(
        "SELECT * FROM media WHERE status='available' AND id>? ORDER BY id LIMIT 500", arrayOf(after))
    fun matched(id: String, film: Film, tmdb: Int, type: String) {
        val category = when(film.category) { "动漫" -> "anime"; "纪录片" -> "documentary"; else -> type }
        writableDatabase.execSQL("UPDATE media SET tmdb=?,type=?,title=?,year=?,synopsis=?,poster=?,metadata=?,scrape_code='MATCHED',confidence=0.95 WHERE id=? AND manual=0",
            arrayOf(tmdb, category, film.title, film.year, film.synopsis, film.artworkUrl, PersonalMediaProjection.metadata(film, type).toString(), id))
    }
    fun scrapeStatus(id: String, code: String) {
        writableDatabase.execSQL("UPDATE media SET scrape_code=? WHERE id=? AND manual=0", arrayOf(code, id))
    }
    fun nfoHint(folder: String, nfo: String, hint: PersonalNfoIdentity.Hint) {
        val parent = nfo.substringBeforeLast('/', "")
        val stem = nfo.substringAfterLast('/').substringBeforeLast('.')
        val broad = stem.equals("tvshow", true) && hint.type == "tv"
        val generic = broad || stem.equals("movie", true) && hint.type == "movie"
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val scope = if (generic) prefix else prefix + stem + "."
        var after = ""
        while (true) {
            val page = rows(
                """SELECT id,path,metadata FROM media WHERE folder=? AND id>? AND status='available' AND tmdb IS NULL
                    AND manual=0 AND substr(path,1,length(?))=? ORDER BY id LIMIT 200""",
                arrayOf(folder, after, scope, scope))
            if (page.isEmpty()) break
            for (row in page) {
                val path = row.getString("path")
                if (!broad && path.substringBeforeLast('/', "") != parent) continue
                if (!generic && path.substringAfterLast('/').substringBeforeLast('.') != stem) continue
                val previous = runCatching { JSONObject(row.optString("metadata")) }.getOrDefault(JSONObject())
                if (previous.optBoolean("nfoExact") && generic) continue
                val metadata = JSONObject().put("nfoTmdb", hint.tmdb).put("nfoType", hint.type).put("nfoExact", !generic)
                if (previous.optInt("nfoTmdb") == hint.tmdb && previous.optString("nfoType") == hint.type &&
                    previous.optBoolean("nfoExact") == !generic) continue
                writableDatabase.execSQL("UPDATE media SET metadata=?,scrape_next=0 WHERE id=? AND tmdb IS NULL AND manual=0",
                    arrayOf(metadata.toString(), row.getString("id")))
            }
            after = page.last().getString("id")
        }
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
            // A manual reassignment cannot retain the previous work's cast,
            // backdrop or original media-type namespace.
            putNull("metadata"); put("scrape_code", "MANUAL_MATCH"); put("scrape_next", 0L)
            if(year != null) put("year", year)
            if(poster.isEmpty()) putNull("poster") else put("poster", poster)
            put("synopsis", synopsis.take(2000))
        }
        writableDatabase.update("media", values, "id=?", arrayOf(id))
    }
}
