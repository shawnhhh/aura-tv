package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Persistent index first. Network enumeration runs only on an explicit import/refresh. */
internal class PersonalLibrary(context: Context) {
    private val app = context.applicationContext
    val connections = PersonalConnections(app)
    val index = PersonalMediaIndex.shared(app)
    init { connections.migrate(index) }
    fun sources(streams: HerrinSmbSessions): List<HerrinPersonalSource> = connections.list().filter { it.enabled }.map { c ->
        object : HerrinPersonalSource {
            override val id = "personal:${c.id}"
            override val type = HerrinSourceType.NAS
            override suspend fun search(identity: HerrinFilmIdentity): List<HerrinSourceMovie> = withContext(Dispatchers.IO) {
                val enabled = index.folders(c.id).filter { it.enabled }.map { it.id }.toSet()
                index.candidates(c.id, identity).mapNotNull { row ->
                    if(row.getString("folder") !in enabled) return@mapNotNull null
                    val offer = offer(c, row, id)
                    if(row.optInt("tmdb") > 0) offer.takeIf { row.optInt("tmdb") == identity.tmdbId && offer.mediaType == identity.mediaType }?.let {
                        it.copy(film = it.film.copy(title = identity.names.first(), year = identity.year?.toString().orEmpty()))
                    }
                    else offer.takeIf { HerrinFilmIdentityMatch.matches(identity, it) }
                }
            }
            override fun playback(offer: HerrinSourceMovie): PlaybackResult {
                if(offer.sourceId != id) return PlaybackResult.Unavailable("媒体连接不匹配")
                val row = index.find(offer.film.id) ?: return PlaybackResult.Unavailable("文件索引已失效")
                return media(c, row, streams)
            }
        }
    }
    private fun offer(c: PersonalConnections.Connection, row: JSONObject, sourceId: String): HerrinSourceMovie {
        val episode = row.optInt("episode").takeIf { it > 0 }
        val type = if(row.getString("type") in setOf("tv", "anime")) "tv" else "movie"
        val film = Film(row.getString("id"), row.getString("title"), row.getString("name"),
            if(type == "tv") "剧集" else "电影", row.optString("year"), row.optString("synopsis"), R.drawable.photofilm_orbit, artworkUrl = row.optString("poster").takeIf { it.isNotBlank() },
            playbackEpisodes = listOf(PlaybackEpisode(episode ?: 1, row.getString("name"),
                listOf(PlaybackLine(sourceId, c.name, "personal:${row.getString("id")}")))))
        return HerrinSourceMovie(sourceId, HerrinSourceType.NAS, film, type,
            season = row.optInt("season").takeIf { it > 0 }, episode = episode,
            resolutionClaim = HerrinFilmIdentityMatch.resolutionClaim(row.getString("name")),
            hdrClaim = Regex("(?i)(?:^|[ ._-])(HDR|HDR10|DV)(?:$|[ ._-])").containsMatchIn(row.getString("name")))
    }
    fun play(id: String, streams: HerrinSmbSessions): PlaybackResult {
        val row = index.find(id) ?: return PlaybackResult.Unavailable("媒体不存在")
        val c = connections.get(row.getString("connection")) ?: return PlaybackResult.Unavailable("连接已删除")
        return media(c, row, streams)
    }
    private fun media(c: PersonalConnections.Connection, row: JSONObject, streams: HerrinSmbSessions): PlaybackResult {
        val path = row.getString("path")
        val folder = index.folders(c.id).firstOrNull { it.id == row.getString("folder") && it.enabled }
        if(!c.enabled || folder == null || !PersonalStoragePolicy.contains(folder.path, path)) return PlaybackResult.Unavailable("连接或媒体目录已停用")
        return if(c.protocol == "smb") runCatching {
            val account = HerrinSmbAccount(c.address(folder.path), c.username, c.password, c.domain, c.encryption)
            PlaybackResult.Ready(streams.media(account, path.substringAfter('/').replace('/', '\\')))
        }.getOrElse { PlaybackResult.Unavailable("SMB 文件不可读") }
        else PlaybackResult.Ready(PlaybackMedia(c.address(path), headers = if(c.username.isBlank()) emptyMap() else
            mapOf("Authorization" to okhttp3.Credentials.basic(c.username, c.password)), allowHttpRedirects = false))
    }
    fun refresh(folderId: String) = synchronized(scanLock) {
        check(active == null || active?.isCompleted == true) { "SCAN_BUSY" }
        val f = index.folders().first { it.id == folderId && it.enabled }
        val c = requireNotNull(connections.get(f.connection)).also { require(it.enabled) }
        active = scope.launch {
            val stamp = System.currentTimeMillis()
            var count = 0
            try {
                index.state(f.id, "scanning")
                val queue = ArrayDeque<Pair<String, Int>>()
                val visited = HashSet<String>()
                queue.add(f.path to 0)
                while(queue.isNotEmpty()) {
                    ensureActive()
                    val (path, depth) = queue.removeFirst()
                    if(!visited.add(path)) continue
                    check(visited.size <= 10000) { "DIRECTORY_LIMIT" }
                    for(file in PersonalRemoteFiles.list(c, path)) {
                        ensureActive()
                        require(PersonalStoragePolicy.contains(f.path, file.path))
                        if(file.directory) {
                            check(depth < 16 && queue.size < 10000) { "DEPTH_LIMIT" }
                            queue.add(file.path to depth + 1)
                        } else if(file.name.substringAfterLast('.').lowercase() in PersonalStoragePolicy.extensions) {
                            check(count < 100000) { "MEDIA_LIMIT" }
                            index.record(f, file, stamp); count++
                            progress = "已扫描 $count 个视频 · ${visited.size} 个目录"
                        }
                    }
                }
                index.finish(f.id, stamp)
                progress = "扫描完成：$count 个视频；正在匹配资料"
                scrape(f)
                progress = "扫描完成：$count 个视频"
            } catch(cancel: CancellationException) {
                index.state(f.id, "cancelled"); progress = "扫描已取消；再次刷新可恢复，已保存索引保留"
                throw cancel
            } catch(error: Exception) {
                index.state(f.id, "offline"); progress = PersonalRemoteFiles.message(error)
                // No deletion on failure, cancellation, limits or an offline server.
            }
        }
    }
    private suspend fun scrape(folder: PersonalMediaIndex.Folder) {
        val token = Preferences(app).tmdbReadToken
        if(token.isBlank()) return
        val discovery = HerrinTmdbDiscovery(cacheDir = app.cacheDir)
        // At most 200 unmatched files per explicit refresh, serial requests; failures retain raw files.
        val cache = LinkedHashMap<String, Film?>()
        for(row in index.unmatched(folder.id)) {
            currentCoroutineContext().ensureActive()
            val title = row.getString("title")
            val key = title + "|" + row.optString("year") + "|" + row.getString("type")
            val film = if(cache.containsKey(key)) cache[key] else {
                val result = try {
                    val expected = HerrinFilmIdentity(1, if(row.getString("type") in setOf("tv", "anime")) "tv" else "movie",
                        listOf(title), row.optInt("year").takeIf { it > 0 })
                    val matches = discovery.search(title, token).films.filter { f ->
                        val identity = discovery.identity(f) ?: return@filter false
                        HerrinFilmIdentityMatch.matches(expected, HerrinSourceMovie("tmdb", HerrinSourceType.NAS, f, identity.first,
                            titleAliases = listOfNotNull(f.originalDisplay?.title)))
                    }
                    matches.singleOrNull()
                } catch(cancel: CancellationException) { throw cancel }
                catch(_: Exception) { null }
                cache[key] = result
                result
            }
            if(film != null) discovery.identity(film)?.let { (type, id) -> index.matched(row.getString("id"), film, id, type) }
        }
    }
    fun cancelAndWait() { runBlocking { active?.cancelAndJoin() } }
    fun cancel() { active?.cancel() }
    companion object {
        private val scanLock = Any()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        @Volatile private var active: Job? = null
        @Volatile var progress: String = "尚未扫描"
            private set
    }
}
