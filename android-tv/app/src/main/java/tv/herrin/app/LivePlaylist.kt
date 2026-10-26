package tv.herrin.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LiveChannel(val name: String, val url: String, val group: String = "")

sealed interface LivePlaylistResult {
    data class Ready(val channels: List<LiveChannel>) : LivePlaylistResult
    data class Error(val reason: String) : LivePlaylistResult
}

/** Strict user-supplied IPTV M3U, distinct from a video's HLS segment manifest. */
internal object AuthorizedLivePlaylist {
    private const val MAX_CHANNELS = 250
    private val group = Regex("""group-title="([^"]{0,60})"""")

    fun decode(raw: String): List<LiveChannel> {
        require(raw.toByteArray(Charsets.UTF_8).size <= 512_000) { "Playlist too large" }
        val lines = raw.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        require(lines.firstOrNull() == "#EXTM3U") { "Invalid M3U" }
        require(lines.none { it.startsWith("#EXT-X-") }) { "HLS video manifest is not a channel list" }
        val result = ArrayList<LiveChannel>()
        val urls = HashSet<String>()
        var pendingName: String? = null
        var pendingGroup = ""
        for (line in lines.drop(1)) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val duration = line.substringAfter(':').substringBefore(',').substringBefore(' ').toDoubleOrNull()
                if (duration != null && duration > 0) {
                    pendingName = null
                    continue
                }
                // A comma inside a quoted metadata value is not a title delimiter.
                var quoted = false
                var comma = -1
                line.forEachIndexed { index, character ->
                    if (character == '"') quoted = !quoted
                    if (character == ',' && !quoted) comma = index
                }
                val label = if (comma >= 0) line.substring(comma + 1).trim() else ""
                pendingName = label.takeIf { it.isNotEmpty() && it.length <= 80 && it.none { c -> c.code < 32 } }
                pendingGroup = group.find(line)?.groupValues?.get(1).orEmpty()
            } else if (!line.startsWith('#') && line.isNotEmpty()) {
                val name = pendingName
                val url = AuthorizedUrls.https(line)
                if (name != null && url != null && urls.add(url)) {
                    require(result.size < MAX_CHANNELS) { "Too many channels" }
                    result += LiveChannel(name, url, pendingGroup)
                }
                pendingName = null
                pendingGroup = ""
            }
        }
        require(result.isNotEmpty()) { "No authorized HTTPS channels" }
        return result
    }
}

/** Fetches only a user-entered HTTPS playlist: no discovery or embedded providers. */
class RemoteLivePlaylistRepository(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val timeoutMs: Int = 9_000
) {
    suspend fun load(rawUrl: String): LivePlaylistResult = withContext(Dispatchers.IO) {
        val url = AuthorizedUrls.https(rawUrl)
            ?: return@withContext LivePlaylistResult.Error("请输入合法的 HTTPS 直播列表地址")
        var connection: HttpURLConnection? = null
        try {
            val active = openConnection(url).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain")
            }
            connection = active
            if (active.responseCode != 200) throw IOException("Unavailable playlist")
            val type = active.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && type !in setOf(
                    "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl",
                    "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream"))
                throw IOException("Unsupported playlist content type")
            val bytes = active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 512_000) throw IOException("Playlist too large")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            LivePlaylistResult.Ready(AuthorizedLivePlaylist.decode(bytes.toString(Charsets.UTF_8)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LivePlaylistResult.Error("直播列表加载失败，请检查授权链接和网络")
        } finally {
            connection?.disconnect()
        }
    }
}
