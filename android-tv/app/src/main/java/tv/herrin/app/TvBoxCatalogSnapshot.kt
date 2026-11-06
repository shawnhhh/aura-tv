package tv.herrin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Display-only snapshot; playback URLs and credentials are never persisted. */
internal class TvBoxCatalogSnapshot(private val context: Context, private val maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
    private fun file(root: String, site: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((root + "\u0000" + site).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "tvbox-home-snapshot"), "$digest.json")
    }

    private fun publicImage(url: String?): String? = url?.takeIf {
        it.length <= 2048 && runCatching {
            val u = URI(it)
            (u.scheme == "https" || u.scheme == "http") &&
                !u.host.isNullOrBlank() && u.rawUserInfo == null &&
                u.rawQuery == null && u.rawFragment == null
        }.getOrDefault(false)
    }

    fun save(root: String, site: String, films: List<Film>) {
        if (root.isBlank() || site.isBlank() || films.isEmpty()) return
        val entries = JSONArray()
        films.take(100).forEach { film ->
            if (!film.id.matches(Regex("[\\p{L}\\p{N}_.:-]{1,200}"))) return@forEach
            entries.put(JSONObject()
                .put("id", film.id).put("title", film.title.take(180))
                .put("subtitle", film.subtitle.take(160))
                .put("category", film.category.take(40))
                .put("year", film.year.take(12))
                .put("synopsis", film.synopsis.take(1500))
                .put("artwork", publicImage(film.artworkUrl) ?: "")
                .put("backdrop", publicImage(film.backdropUrl) ?: "")
                .put("metadata", film.metadataSource == "TMDB")
                .put("rating", if (film.ratingProvider == "TMDB") film.ratingValue else JSONObject.NULL)
                .put("runtime", film.runtimeMinutes ?: JSONObject.NULL))
        }
        if (entries.length() == 0) return
        val target = file(root, site)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(JSONObject().put("schema", 1)
            .put("updated", System.currentTimeMillis()).put("films", entries).toString())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun load(root: String, site: String): List<Film> {
        if (root.isBlank() || site.isBlank()) return emptyList()
        return runCatching {
            val source = file(root, site)
            if (!source.isFile || source.length() !in 1..250_000) return@runCatching emptyList()
            val json = JSONObject(source.readText())
            if (json.optInt("schema") != 1 ||
                System.currentTimeMillis() - json.optLong("updated") !in 0..maxAgeMs)
                return@runCatching emptyList()
            val data = json.getJSONArray("films")
            (0 until minOf(100, data.length())).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id").takeIf {
                    it.matches(Regex("[\\p{L}\\p{N}_.:-]{1,200}"))
                } ?: return@mapNotNull null
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Film(id, title, item.optString("subtitle"), item.optString("category"),
                    item.optString("year"), item.optString("synopsis"),
                    R.drawable.photofilm_orbit, artworkUrl = publicImage(item.optString("artwork")),
                    backdropUrl = publicImage(item.optString("backdrop")),
                    metadataSource = if (item.optBoolean("metadata")) "TMDB" else null,
                    ratingValue = if (item.isNull("rating")) null else item.optDouble("rating"),
                    ratingProvider = if (item.optBoolean("metadata")) "TMDB" else null,
                    runtimeMinutes = if (item.isNull("runtime")) null else item.optInt("runtime"))
            }
        }.getOrDefault(emptyList())
    }
}
