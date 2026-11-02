package tv.herrin.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Optional personal offline importer for the user's own IMDb non-commercial
 * title.ratings.tsv.gz file. Code is open source; NO IMDb ratings, Top250
 * rankings, branded assets or bulk dataset are bundled/distributed by Herrin.
 *
 * IMPORTANT: IMDb's free dataset license must be reviewed before enabling
 * public redistribution/use. Never advertise this as a licensed public API.
 */
internal data class HerrinImdbScore(val value: Double, val votes: Int)

internal object HerrinImdbTsv {
    fun row(line: String): Pair<String, HerrinImdbScore>? {
        val columns = line.split('\t')
        if (columns.size != 3 || !columns[0].matches(Regex("""tt\d{7,10}"""))) return null
        val score = columns[1].toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 1.0..10.0 } ?: return null
        val votes = columns[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return columns[0] to HerrinImdbScore(score, votes)
    }
}

internal class HerrinImdbRatings(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, "herrin-imdb-personal-ratings.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ratings(id TEXT PRIMARY KEY NOT NULL, score REAL NOT NULL, votes INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only add future migrations here. Never wipe a user's imported data.
        error("Unsupported IMDb database version")
    }

    fun lookup(id: String?): HerrinImdbScore? {
        if (id == null || !id.matches(Regex("""tt\d{7,10}"""))) return null
        return readableDatabase.rawQuery("SELECT score, votes FROM ratings WHERE id=?",
            arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) HerrinImdbScore(cursor.getDouble(0), cursor.getInt(1))
            else null
        }
    }

    /**
     * User picks the official .tsv.gz file using Android SAF (not an auto
     * download). Stream rows to SQLite without keeping a huge TSV in RAM.
     * A failed/interrupted import keeps the previous successful ratings table.
     */
    suspend fun importUserFile(uri: Uri): Int = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open user-selected IMDb ratings dataset")
        stream.use { importGzip(it) }
    }

    internal fun importGzip(stream: InputStream): Int {
        val db = writableDatabase
        db.execSQL("DROP TABLE IF EXISTS ratings_new")
        db.execSQL("CREATE TABLE ratings_new(id TEXT PRIMARY KEY NOT NULL, score REAL NOT NULL, votes INTEGER NOT NULL)")
        var imported = 0
        var rows = 0
        try {
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
                db.beginTransaction()
                try {
                    val insert = db.compileStatement(
                        "INSERT OR REPLACE INTO ratings_new(id,score,votes) VALUES(?,?,?)")
                    insert.use {
                        for (line in reader.lineSequence()) {
                            rows++
                            if (rows > 20_000_000) error("IMDb dataset row limit exceeded")
                            if (line.length > 180) error("Invalid IMDb TSV row length")
                            val entry = HerrinImdbTsv.row(line) ?: continue
                            it.clearBindings()
                            it.bindString(1, entry.first)
                            it.bindDouble(2, entry.second.value)
                            it.bindLong(3, entry.second.votes.toLong())
                            it.executeInsert()
                            imported++
                            if (imported % 2000 == 0) {
                                db.setTransactionSuccessful()
                                db.endTransaction()
                                db.beginTransaction()
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            check(imported > 0) { "No valid IMDb ratings found in user-supplied file" }
            // Atomic table replacement at the end of a fully parsed gzip file.
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE ratings RENAME TO ratings_old")
                db.execSQL("ALTER TABLE ratings_new RENAME TO ratings")
                db.execSQL("DROP TABLE ratings_old")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return imported
        } catch (error: Exception) {
            db.execSQL("DROP TABLE IF EXISTS ratings_new")
            throw error
        }
    }
}
