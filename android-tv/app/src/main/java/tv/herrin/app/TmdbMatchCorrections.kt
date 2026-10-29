package tv.herrin.app

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Explicit user correction, not a fuzzy match. Carries no provider URL or credential. */
internal data class TmdbManualMatch(val mediaType: String, val id: Int) {
    init { require(mediaType in listOf("movie", "tv") && id > 0) }
}

/** App-private, bounded persistence. Hashed source identity keeps filenames free of private IDs. */
internal class TmdbMatchCorrections(private val directory: File) {
    @Synchronized fun get(film: Film): TmdbManualMatch? = runCatching {
        val entry = file(film)
        if (!entry.isFile || entry.length() !in 1..512) return null
        val json = JSONObject(entry.readText())
        TmdbManualMatch(json.getString("type"), json.getInt("id"))
    }.getOrNull()

    @Synchronized fun set(film: Film, match: TmdbManualMatch?) {
        val entry = file(film)
        if (match == null) { entry.delete(); return }
        directory.mkdirs()
        val existing = directory.listFiles().orEmpty().filter { it.extension == "json" }
        if (existing.size >= 500 && !entry.exists())
            existing.minByOrNull { it.lastModified() }?.delete()
        val temporary = File(directory, "${entry.name}.tmp")
        temporary.writeText(JSONObject().put("type", match.mediaType).put("id", match.id).toString())
        check(temporary.renameTo(entry)) { "无法保存影片匹配" }
    }

    private fun file(film: Film): File {
        val title = film.originalDisplay?.title ?: film.title
        val identity = "${film.id}|$title|${film.spiderVodId.orEmpty()}|${film.cmsVodId.orEmpty()}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
}
