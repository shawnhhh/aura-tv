package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * An explicit, user-configured, rights-authorized HTTPS catalog.
 * Nothing here discovers, scrapes, or unlocks third-party streaming sources.
 */
object AuthorizedUrls {
    fun https(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank() || text.length > 2048) return null
        return try {
            val uri = URI(text)
            if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.fragment != null ||
                uri.port !in -1..65535 || uri.port == 0 || uri.rawAuthority.contains('%')) null else uri.toASCIIString()
        } catch (_: Exception) { null }
    }
}

data class LoadedCatalog(val films: List<Film>, val fromCache: Boolean)
sealed interface CatalogLoadResult {
    data class Ready(val catalog: LoadedCatalog) : CatalogLoadResult
    data class Error(val reason: String) : CatalogLoadResult
}

/** Public JSON schema: { "version": 1, "items": [ {...} ] }. */
object CatalogJson {
    private val ids = Regex("^[a-zA-Z0-9._-]{1,64}$")
    fun decode(raw: String): List<Film> {
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == 1) { "目录版本必须为 1" }
        val entries = root.getJSONArray("items")
        require(entries.length() in 1..100) { "目录应包含 1–100 个影片" }
        val result = ArrayList<Film>(entries.length())
        val used = HashSet<String>()
        val placeholders = intArrayOf(
            R.drawable.photofilm_orbit, R.drawable.photofilm_tide,
            R.drawable.photofilm_dawn, R.drawable.photofilm_night,
            R.drawable.photofilm_return, R.drawable.photofilm_blue
        )
        repeat(entries.length()) { i ->
            val entry = entries.getJSONObject(i)
            val id = entry.getString("id").trim()
            val title = entry.getString("title").trim()
            require(id.matches(ids) && used.add(id)) { "影片 ID 必须唯一且合法" }
            require(title.isNotBlank() && title.length <= 80) { "影片名称不合法" }
            val category = entry.optString("category", "电影")
            require(category in listOf("电影", "剧集", "动漫")) { "不支持的影片分类" }
            val subtitle = entry.optString("subtitle", "").take(100)
            val synopsis = entry.optString("synopsis", "").take(800)
            val year = entry.optString("year", "").take(16)
            val episodes = entry.optInt("episodes", 1).coerceIn(1, 99)
            val poster = entry.optString("artwork_url", "").takeIf { it.isNotBlank() }
                ?.let { AuthorizedUrls.https(it) }
            val licensedEpisodes = PlaybackCatalogJson.decode(entry)
            result += Film(
                id = id, title = title, subtitle = subtitle, category = category,
                year = year, synopsis = synopsis, artwork = placeholders[i % placeholders.size],
                episodes = maxOf(episodes, licensedEpisodes.maxOfOrNull { it.number } ?: 1),
                artworkUrl = poster, playbackEpisodes = licensedEpisodes
            )
        }
        return result
    }
}

class RemoteCatalogRepository(
    private val context: Context,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val timeoutMs: Int = 8000
) {
    suspend fun load(endpoint: String): CatalogLoadResult = withContext(Dispatchers.IO) {
        val safe = AuthorizedUrls.https(endpoint)
            ?: return@withContext CatalogLoadResult.Error("目录地址必须为合法 HTTPS 链接")
        val name = MessageDigest.getInstance("SHA-256").digest(safe.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "authorized-catalog")
        val file = File(dir, "$name.json")
        try {
            val json = fetch(safe)
            val films = CatalogJson.decode(json)
            dir.mkdirs()
            val temp = File(dir, "$name.tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) { file.writeText(json, Charsets.UTF_8); temp.delete() }
            CatalogLoadResult.Ready(LoadedCatalog(films, fromCache = false))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache is per-URL. Never show another user's catalog after switching endpoints.
            val cached = runCatching {
                if (file.length() > 2_000_000) throw IOException("Oversized cache")
                CatalogJson.decode(file.readText(Charsets.UTF_8))
            }.getOrNull()
            if (cached == null) CatalogLoadResult.Error("目录读取失败，已保留离线演示内容")
            else CatalogLoadResult.Ready(LoadedCatalog(cached, fromCache = true))
        }
    }

    private fun fetch(url: String): String {
        val connection = openConnection(url).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != 200) throw IOException("Bad catalog response")
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != "application/json" && type?.endsWith("+json") != true)
                throw IOException("Expected JSON content type")
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                val data = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (data.size() + n > 2_000_000) throw IOException("Oversized catalog")
                    data.write(buffer, 0, n)
                }
                data.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
