package tv.herrin.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

/** One-time, user-selected M3U import; no server or set-top box is needed after import. */
internal object FamilyLiveUrls {
    fun unicast(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.length > 2048 || value.any { it.code < 32 }) return null
        return try {
            val uri = URI(value)
            // Multicast hosts cannot be made into stand-alone family unicast by relabeling the scheme.
            val host = uri.host.orEmpty().removePrefix("[").removeSuffix("]")
            val ipv4FirstOctet = if (host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
                host.substringBefore('.').toIntOrNull() else null
            val multicast = (ipv4FirstOctet != null && ipv4FirstOctet in 224..239) ||
                (host.startsWith("ff", ignoreCase = true) && ':' in host)
            if (uri.scheme?.lowercase() !in setOf("https", "http", "rtsp") ||
                host.isBlank() || multicast || uri.rawUserInfo != null || uri.fragment != null ||
                uri.port == 0 || uri.port !in -1..65535 || uri.rawAuthority.contains('%')) null
            else uri.toASCIIString()
        } catch (_: Exception) { null }
    }
}

internal data class FamilyLiveImport(val channels: List<LiveChannel>, val skipped: Int)

internal object FamilyLivePlaylist {
    const val MAX_BYTES = 512_000
    const val MAX_CHANNELS = 250
    const val FAVORITES_SCOPE = "herrin-family-local"
    private val group = Regex("""group-title="([^"]{0,60})""", RegexOption.IGNORE_CASE)
    private val tvgId = Regex("""tvg-id="([^"]{0,80})""", RegexOption.IGNORE_CASE)

    fun decode(raw: String): FamilyLiveImport {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件超过 512 KB" }
        val lines = raw.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        require(lines.firstOrNull() == "#EXTM3U") { "不是有效的 M3U 频道列表" }
        require(lines.none { it.startsWith("#EXT-X-", ignoreCase = true) }) { "不能把 HLS 视频分片清单作为频道列表" }
        val channels = ArrayList<LiveChannel>()
        val seen = HashSet<String>()
        var skipped = 0
        var pendingName: String? = null
        var pendingGroup = ""
        var pendingId = ""
        for (line in lines.drop(1)) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val duration = line.substringAfter(':').substringBefore(',').substringBefore(' ').toDoubleOrNull()
                if (duration != null && duration > 0) {
                    pendingName = null
                    continue
                }
                // Parse commas outside quoted group metadata, as in the HTTPS loader.
                var quoted = false
                var separator = -1
                line.forEachIndexed { index, char ->
                    if (char == '"') quoted = !quoted
                    if (char == ',' && !quoted) separator = index
                }
                val title = if (separator < 0) "" else line.substring(separator + 1).trim()
                pendingName = title.takeIf { it.isNotEmpty() && it.length <= 80 && it.none { c -> c.code < 32 } }
                pendingGroup = group.find(line)?.groupValues?.get(1).orEmpty()
                pendingId = tvgId.find(line)?.groupValues?.get(1).orEmpty()
            } else if (line.isNotEmpty() && !line.startsWith('#')) {
                val url = FamilyLiveUrls.unicast(line)
                if (pendingName != null && url != null && seen.add(url)) {
                    require(channels.size < MAX_CHANNELS) { "单播频道超过 250 个，请先筛选频道再导入" }
                    channels += LiveChannel(pendingName!!, url, pendingGroup, pendingId)
                } else if (pendingName != null) {
                    skipped++
                }
                pendingName = null
                pendingGroup = ""
                pendingId = ""
            }
        }
        require(channels.isNotEmpty()) { "未发现可直接播放的 HTTP(S)/RTSP 单播频道；RTP/UDP 组播需要常开代理，不符合当前家庭方案" }
        return FamilyLiveImport(channels, skipped)
    }

    suspend fun readDocument(context: Context, uri: Uri): Pair<String, FamilyLiveImport> = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("文件超过 512 KB")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val decoder = Charsets.UTF_8.newDecoder()
        val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw IOException("请使用 UTF-8 编码的 M3U 文件") }
        text to decode(text)
    }
}

/** Persists the complete user-imported list in private app storage; no polling afterward. */
internal class FamilyLiveStore(context: Context) {
    private val prefs = context.getSharedPreferences("herrin", Context.MODE_PRIVATE)
    fun isActive(): Boolean = prefs.getBoolean("family_live_active", false)
    fun hasPlaylist(): Boolean = !prefs.getString("family_live_m3u", "").isNullOrEmpty()
    fun load(): FamilyLiveImport = FamilyLivePlaylist.decode(
        prefs.getString("family_live_m3u", "").orEmpty())

    fun replace(raw: String, parsed: FamilyLiveImport): Boolean {
        // Validate again before replacing. No partial or invalid import is persisted.
        require(parsed.channels.isNotEmpty())
        FamilyLivePlaylist.decode(raw)
        return prefs.edit().putString("family_live_m3u", raw)
            .putBoolean("family_live_active", true).commit()
    }
    fun setActive(active: Boolean): Boolean {
        if (active && !hasPlaylist()) return false
        return prefs.edit().putBoolean("family_live_active", active).commit()
    }
}
