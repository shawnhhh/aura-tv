package tv.herrin.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

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
                    if(row.getString("folder") !in enabled || (row.getString("type") == "video" && row.optInt("tmdb") <= 0)) return@mapNotNull null
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
        val type = runCatching { JSONObject(row.optString("metadata")).optString("identityType") }.getOrNull()
            ?.takeIf { it in setOf("movie", "tv") } ?: if(row.getString("type") in setOf("tv", "anime")) "tv" else "movie"
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
            PlaybackResult.Ready(streams.media(account, path.substringAfter('/').replace('/', '\\')).copy(displayTitle = row.getString("name")))
        }.getOrElse { PlaybackResult.Unavailable("SMB 文件不可读") }
        else PlaybackResult.Ready(PlaybackMedia(c.address(path), headers = if(c.username.isEmpty() && c.password.isEmpty()) emptyMap() else
            mapOf("Authorization" to okhttp3.Credentials.basic(c.username, c.password)), allowHttpRedirects = false, displayTitle = row.getString("name")))
    }
    fun refresh(folderId: String) = synchronized(scanLock) {
        check(active == null || active?.isCompleted == true) { "SCAN_BUSY" }
        val f = index.folders().first { it.id == folderId && it.enabled }
        val c = requireNotNull(connections.get(f.connection)).also { require(it.enabled) }
        active = scope.launch {
            StartupBackgroundWork.awaitReady()
            val stamp = System.currentTimeMillis()
            var count = 0
            try {
                index.state(f.id, "scanning")
                val queue = ArrayDeque<Pair<String, Int>>()
                val visited = HashSet<String>()
                queue.add(f.path to 0)
                progress = "正在读取媒体目录…"
                PersonalRemoteFiles.Session(c, f.path).use { remote ->
                val nfoAfter = index.nfoCursor(f.id)
                val sidecars = java.util.PriorityQueue<PersonalRemoteFile>(compareByDescending { it.path })
                var moreSidecars = false
                while(queue.isNotEmpty()) {
                    ensureActive()
                    StartupBackgroundWork.awaitReady()
                    val (path, depth) = queue.removeFirst()
                    if(!visited.add(path)) continue
                    check(visited.size <= 10000) { "DIRECTORY_LIMIT" }
                    for(file in remote.list(path)) {
                        ensureActive()
                        StartupBackgroundWork.awaitReady()
                        require(PersonalStoragePolicy.contains(f.path, file.path))
                        if(file.directory) {
                            check(depth < 16 && queue.size < 10000) { "DEPTH_LIMIT" }
                            queue.add(file.path to depth + 1)
                        } else if(file.name.endsWith(".nfo", true) && file.size in 1..65536 && file.path > nfoAfter) {
                            sidecars += file
                            if (sidecars.size > 100) { sidecars.poll(); moreSidecars = true }
                        } else if(file.name.substringAfterLast('.').lowercase() in PersonalStoragePolicy.extensions) {
                            check(count < 100000) { "MEDIA_LIMIT" }
                            index.record(f, file, stamp); count++
                            if (count % 32 == 0) publishIndexChange()
                        }
                    }
                }
                val orderedSidecars = sidecars.sortedBy { it.path }
                for (sidecar in orderedSidecars) {
                    ensureActive()
                    StartupBackgroundWork.awaitReady()
                    try { PersonalNfoIdentity.parse(remote.text(sidecar.path))?.let { index.nfoHint(f.id, sidecar.path, it) } }
                    catch(cancel: CancellationException) { throw cancel }
                    catch(_: Exception) { HerrinPlaybackDiagnosticJournal.record("PERSONAL", "NFO_READ_FAILED") }
                }
                index.nfoCursor(f.id, if (moreSidecars) orderedSidecars.last().path else "")
                }
                index.finish(f.id, stamp)
                publishIndexChange()
                connections.status(c.id, "connected")
                progress = "扫描完成：$count 个视频"
                HerrinPlaybackDiagnosticJournal.record("PERSONAL", "SCAN_COMPLETE", "COUNT=$count")
                refreshMetadata()
            } catch(cancel: CancellationException) {
                index.state(f.id, "cancelled"); progress = "扫描已取消；再次刷新可恢复，已保存索引保留"
                throw cancel
            } catch(error: Exception) {
                connections.status(c.id, "failed")
                index.state(f.id, "offline"); progress = PersonalRemoteFiles.message(error)
                // No deletion on failure, cancellation, limits or an offline server.
            } finally { publishIndexChange() }
        }
    }
    fun refreshMetadata(): Boolean = synchronized(scanLock) {
        if (activeMetadata?.isCompleted == false) {
            metadataRequested = true
            return@synchronized true
        }
        activeMetadata = scope.launch {
            do {
                synchronized(scanLock) { metadataRequested = false }
                val token = Preferences(app).tmdbReadToken
                val enabled = connections.list().filter { it.enabled }.map { it.id }.toSet()
                val folders = index.folders().filter { it.enabled && it.connection in enabled }
                try {
                    if (token.isBlank()) {
                        folders.forEach { index.missingToken(it.id) }
                        HerrinPlaybackDiagnosticJournal.record("PERSONAL", "SCRAPE_TOKEN_MISSING")
                    } else {
                        val scraper = PersonalMetadataScraper(index, HerrinTmdbDiscovery(cacheDir = app.cacheDir),
                            token, { Preferences(app).tmdbReadToken }, ::publishIndexChange)
                        var remaining = folders
                        while (remaining.isNotEmpty()) {
                            ensureActive()
                            remaining = remaining.filter { scraper.page(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    HerrinPlaybackDiagnosticJournal.record("PERSONAL", "METADATA_REFRESH_FAILED")
                }
            } while (synchronized(scanLock) { metadataRequested })
        }
        true
    }
    fun cancelAndWait() = runBlocking {
        val jobs = synchronized(scanLock) {
            metadataRequested = false
            listOfNotNull(active, activeMetadata).also { it.forEach { job -> job.cancel() } }
        }
        jobs.joinAll()
    }
    fun cancel() {
        synchronized(scanLock) {
            metadataRequested = false
            active?.cancel()
            activeMetadata?.cancel()
        }
    }
    companion object {
        private val revision = MutableStateFlow(0L)
        val revisions = revision.asStateFlow()
        private fun publishIndexChange() { revision.update { it + 1 } }
        fun metadataChanged() = publishIndexChange()
        private val scanLock = Any()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        @Volatile private var active: Job? = null
        private var activeMetadata: Job? = null
        private var metadataRequested = false
        suspend fun awaitCurrentScan(): Boolean {
            val job = active ?: return true
            job.join()
            return !job.isCancelled
        }
        @Volatile var progress: String = "尚未扫描"
            private set
    }
}
