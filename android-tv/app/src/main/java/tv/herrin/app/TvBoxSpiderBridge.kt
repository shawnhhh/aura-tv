package tv.herrin.app

import android.content.Context
import com.github.catvod.Runtime
import com.github.catvod.Proxy as CatVodProxy
import com.github.catvod.crawler.Spider
import com.github.catvod.net.Net
import com.github.catvod.utils.Local
import com.github.catvod.utils.RuntimeModule
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * GPL-3.0 CatVod adapter. The SDK source is vendored from FongMi/TV@123cc08.
 * User-supplied executable JARs are only run following an explicit source application.
 * External code runs with application permissions: see docs/CATVOD_GPL.md.
 */
internal sealed interface SpiderResult<out T> {
    data class Ready<T>(val value: T) : SpiderResult<T>
    data class Failure(val message: String) : SpiderResult<Nothing>
}

internal object SpiderVodCodec {
    fun listing(json: String, site: TvBoxSite): List<Film> {
        val array = JSONObject(json).optJSONArray("list") ?: return emptyList()
        return (0 until minOf(array.length(), 80)).mapNotNull { index ->
            val vod = array.optJSONObject(index) ?: return@mapNotNull null
            val id = vod.optString("vod_id").trim().take(512)
            val title = vod.optString("vod_name").trim().take(120)
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            Film(id = "spider-${site.key.hashCode().toUInt().toString(16)}-${id.hashCode().toUInt().toString(16)}-$index",
                title = title, subtitle = vod.optString("vod_remarks").take(80),
                category = TvBoxCategories.topLevel(vod.optString("type_name")),
                year = vod.optString("vod_year").take(10),
                synopsis = SourceText.synopsis(vod.optString("vod_content")),
                artwork = R.drawable.photofilm_orbit,
                artworkUrl = TvBoxUrl.normalize(vod.optString("vod_pic"), allowHttp = true),
                backdropUrl = SourceArtwork.backdrop(vod, allowHttp = true),
                spiderVodId = id)
        }
    }

    fun detail(json: String, film: Film): Film {
        val item = JSONObject(json).optJSONArray("list")?.optJSONObject(0)
            ?: error("Spider 详情缺少 list[0]")
        val flags = item.optString("vod_play_from").split("$$$").take(12)
        val episodes = linkedMapOf<Int, MutableList<PlaybackLine>>()
        val episodeTitles = linkedMapOf<Int, String>()
        item.optString("vod_play_url").split("$$$").take(12).forEachIndexed { lineIndex, playlist ->
            val flag = flags.getOrNull(lineIndex).orEmpty().ifBlank { "线路 ${lineIndex + 1}" }.take(80)
            playlist.split('#').take(300).forEachIndexed { index, entry ->
                val separator = entry.indexOf('$')
                val episodeName = if (separator >= 0) entry.substring(0, separator).trim() else ""
                val opaqueId = (if (separator >= 0) entry.substring(separator + 1) else entry).trim()
                if (opaqueId.isNotEmpty() && opaqueId.length <= 2_048) {
                    if (episodeName.isNotEmpty() && index + 1 !in episodeTitles)
                        episodeTitles[index + 1] = episodeName.replace(Regex("<[^>]*>"), " ").take(80)
                    episodes.getOrPut(index + 1) { mutableListOf() }.add(
                        PlaybackLine("spider-$lineIndex", flag, opaqueId))
                }
            }
        }
        val playback = episodes.map { (number, lines) ->
            PlaybackEpisode(number, episodeTitles[number]?.takeIf { it.isNotBlank() }
                ?: "第 $number 集", lines)
        }
        val detailedCategory = item.optString("type_name").trim()
        return film.copy(
            synopsis = SourceText.synopsis(item.optString("vod_content"))
                .ifBlank { film.synopsis },
            year = item.optString("vod_year").trim().take(10).ifBlank { film.year },
            subtitle = item.optString("vod_remarks").trim().take(80).ifBlank { film.subtitle },
            category = if (detailedCategory.isNotBlank()) TvBoxCategories.topLevel(detailedCategory)
                else film.category,
            artworkUrl = TvBoxUrl.normalize(item.optString("vod_pic"), allowHttp = true)
                ?: film.artworkUrl,
            backdropUrl = SourceArtwork.backdrop(item, allowHttp = true) ?: film.backdropUrl,
            episodes = maxOf(1, playback.size), playbackEpisodes = playback)
    }

    fun player(json: String, allowHttp: Boolean,
               siteHeaders: Map<String, String> = emptyMap()): PlaybackResult {
        val data = JSONObject(json)
        val options = playerUrls(data.opt("url")).mapNotNull {
            TvBoxUrl.normalize(it, allowHttp)
        }.distinct().take(8)
        val needsWebParser = data.optInt("parse", 0) != 0 || data.optInt("jx", 0) != 0
        // A provider occasionally sets parse=1 even while returning a direct
        // media file. Accept an actual manifest/file URL; never open a web
        // parsing page, sniff a browser, or invoke an unknown third-party JX.
        val playable = if (!needsWebParser) options else options.filter {
            it.substringBefore('?').substringBefore('#').lowercase().let { path ->
                path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd")
            }
        }
        if (needsWebParser && playable.isEmpty())
            return PlaybackResult.Unavailable("此线路需要网页解析器，请切换返回直链的授权线路")
        val uri = playable.firstOrNull()
            ?: return PlaybackResult.Unavailable("Spider 没有返回本次授权的 HTTP(S) 直链")
        val responseHeaders = TvBoxHeaderCodec.decode(
            data.optJSONObject("header") ?: data.optJSONObject("headers"))
        // FongMi Result.headers() returns the Site.header fallback only when
        // the player result supplies no explicit headers. Avoid key-by-key
        // merging: it could accidentally forward stale authentication values.
        val headers = responseHeaders.ifEmpty { siteHeaders }
        val tracks = data.optJSONArray("subs") ?: JSONArray()
        val subtitles = (0 until minOf(tracks.length(), 6)).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val rawUrl = track.optString("url")
            val subtitleUrl = TvBoxUrl.normalize(rawUrl, allowHttp) ?: return@mapNotNull null
            // This Media3 panel currently configures WebVTT only. Do not pass
            // SRT/ASS tracks to a VTT decoder and report them as working.
            val format = track.optString("format").lowercase()
            if (format.isNotBlank() && format != "text/vtt" &&
                format != "application/octet-stream") return@mapNotNull null
            if (!subtitleUrl.substringBefore('?').lowercase().endsWith(".vtt") && format != "text/vtt")
                return@mapNotNull null
            val lang = track.optString("lang").trim().lowercase().ifBlank { "und" }
            if (!lang.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) return@mapNotNull null
            val name = track.optString("name").trim().take(64).ifBlank { "字幕 ${index + 1}" }
            AuthorizedSubtitle(lang, name, subtitleUrl)
        }
        return PlaybackResult.Ready(PlaybackMedia(uri, headers, subtitles,
            alternateUris = playable.drop(1), allowHttpRedirects = allowHttp,
            declaredMime = PlaybackFormatRetry.declaredMime(data.optString("format")),
            danmakuSources = DanmakuProtocol.decodeSources(data.opt("danmaku"))))
    }

    /** FongMi CatVod permits a URL string, alternating label/URL pairs, or Url.values. */
    private fun playerUrls(raw: Any?): List<String> = when (raw) {
        is String -> listOf(raw)
        is JSONArray -> (1 until raw.length() step 2).map { raw.optString(it) }
        is JSONObject -> {
            val choices = raw.optJSONArray("values") ?: JSONArray()
            val urls = (0 until minOf(choices.length(), 20)).mapNotNull {
                choices.optJSONObject(it)?.optString("v")
            }
            val selected = raw.optInt("position", 0)
            if (selected in urls.indices) listOf(urls[selected]) + urls.filterIndexed { i, _ -> i != selected }
            else urls
        }
        else -> emptyList()
    }
}

/** A bounded catalog fallback for plugins whose home page has categories but no films. */
internal object SpiderCatalogBrowser {
    private const val MAX_CATEGORIES = 6

    fun browse(site: TvBoxSite, home: (Boolean) -> String, homeVideos: () -> String,
               category: (String) -> String): List<Film> {
        val failures = mutableListOf<String>()
        val categories = linkedMapOf<String, String>()

        fun attempt(label: String, block: () -> String): List<Film> {
            val payload = try { block() }
            catch (error: Exception) {
                failures += "$label：${SpiderFailureDetails.message(error).take(90)}"
                return emptyList()
            } catch (error: LinkageError) {
                failures += "$label：${SpiderFailureDetails.message(error).take(90)}"
                return emptyList()
            }
            if (payload.isBlank()) return emptyList()
            return try {
                val root = JSONObject(payload)
                val groups = root.optJSONArray("class")
                if (groups != null) for (index in 0 until minOf(groups.length(), 80)) {
                    val group = groups.optJSONObject(index) ?: continue
                    val id = group.optString("type_id").trim().take(128)
                    if (id.isNotBlank()) categories.putIfAbsent(id,
                        group.optString("type_name").take(80))
                }
                SpiderVodCodec.listing(payload, site)
            } catch (error: Exception) {
                failures += "$label：返回内容不是有效影视 JSON"
                emptyList()
            }
        }

        attempt("首页", { home(true) }).takeIf { it.isNotEmpty() }?.let { return it }
        attempt("首页推荐", homeVideos).takeIf { it.isNotEmpty() }?.let { return it }
        // TVBox plugins differ on whether a filtered home call exposes a catalog.
        attempt("首页无筛选", { home(false) }).takeIf { it.isNotEmpty() }?.let { return it }

        val preferred = listOf("电影", "剧", "动漫", "综艺", "影视", "短剧")
        val ordered = categories.entries.sortedBy { entry ->
            if (preferred.any { entry.value.contains(it) }) 0 else 1
        }
        for ((id, name) in ordered.take(MAX_CATEGORIES)) {
            attempt("分类 ${name.ifBlank { id }.take(24)}", { category(id) })
                .takeIf { it.isNotEmpty() }?.let { return it }
        }
        val reason = failures.take(3).joinToString("；").take(250)
        error(if (reason.isNotEmpty()) "Spider 无影片；$reason" else
            "Spider 首页及 ${minOf(categories.size, MAX_CATEGORIES)} 个分类均无影片")
    }
}

internal object SpiderNativeLibraryPlan {
    fun assetForAbi(abi: String): String? = when {
        abi.startsWith("arm64") -> "assets/ftyguard_v8.so"
        abi.startsWith("armeabi") -> "assets/ftyguard_v7.so"
        else -> null
    }

    // DexClassLoader's native library lookup requires the lib*.so basename.
    fun filenameForAsset(asset: String): String = "lib${asset.substringAfterLast('/')}"
}

/** Verifies the remote DEX container's declared MD5 before any class loading. */
internal object SpiderJarCache {
    private const val MAX_JAR_BYTES = 16 * 1024 * 1024
    private val md5Pattern = Regex("[0-9a-fA-F]{32}")

    fun obtain(context: Context, spec: String): File {
        val fragments = spec.split(";md5;", limit = 2)
        require(fragments.size == 2 && fragments[1].trim().matches(md5Pattern)) {
            "安全校验：此 Spider 源必须提供 ;md5; 加 32 位校验值"
        }
        val url = TvBoxUrl.normalize(fragments[0].trim())
            ?: error("Spider JAR 必须由 HTTPS 下载")
        val md5 = fragments[1].trim().lowercase()
        val folder = File(context.codeCacheDir, "herrin-spider")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建 Spider 缓存目录" }
        val destination = File(folder, "$md5.jar")
        if (destination.isFile && validJar(destination, md5)) {
            check(destination.setReadOnly()) { "无法保护 Spider 缓存文件" }
            return destination
        }
        val temporary = File(folder, "$md5.download")
        if (temporary.exists() && !temporary.delete()) error("无法清理旧下载")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            check(connection.responseCode == 200) { "Spider 下载返回 HTTP ${connection.responseCode}" }
            check(connection.contentLengthLong <= MAX_JAR_BYTES) { "Spider 超过 16 MB 限制" }
            val md = MessageDigest.getInstance("MD5")
            var bytes = 0
            temporary.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        check(bytes <= MAX_JAR_BYTES) { "Spider 下载超过 16 MB 限制" }
                        md.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(bytes > 0 && md.digest().joinToString("") { "%02x".format(it) } == md5) {
                "Spider 文件 MD5 不一致，已拒绝加载"
            }
            check(isDexArchive(temporary)) { "Spider 文件不是包含 classes.dex 的 ZIP/APK" }
            if (destination.exists() && !destination.delete()) error("无法替换旧 Spider 缓存")
            check(temporary.renameTo(destination)) { "无法保存 Spider 缓存" }
            // Android 14+ requires dynamically loaded DEX/APKs be read-only.
            check(destination.setReadOnly()) { "无法保护 Spider 缓存文件" }
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validJar(file: File, expectedMd5: String): Boolean {
        if (file.length() !in 1..MAX_JAR_BYTES.toLong() || !isDexArchive(file)) return false
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                digest.update(chunk, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expectedMd5
    }

    private fun isDexArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { it.getEntry("classes.dex") != null }
    }.getOrDefault(false)

    /** Some older protected CatVod packs ship JNI under assets rather than lib/<abi>. */
    fun nativeLibrary(context: Context, jar: File): File? {
        val abi = SpiderRuntimeCompat.processAbi().orEmpty()
        val entryName = SpiderNativeLibraryPlan.assetForAbi(abi)
            ?: return null // Never feed ARM machine code to an x86 emulator.
        return ZipFile(jar).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use null
            require(entry.size in 1..(512 * 1024L)) { "Spider native library size invalid" }
            val folder = File(context.codeCacheDir, "herrin-spider-native/${jar.nameWithoutExtension}")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建 Spider native 缓存" }
            val target = File(folder, SpiderNativeLibraryPlan.filenameForAsset(entryName))
            // An equal-size stale/corrupted cache must not bypass the verified JAR.
            val expectedHash = zip.getInputStream(entry).use(::sha256)
            if (target.length() == entry.size && target.canRead() &&
                target.inputStream().use(::sha256).contentEquals(expectedHash)) return@use target
            val tmp = File(folder, "${target.name}.tmp")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(tmp).use { output ->
                    val count = input.copyTo(output, 8192)
                    check(count == entry.size) { "Spider native library incomplete" }
                    output.fd.sync()
                }
            }
            if (target.exists()) check(target.delete()) { "无法更新 Spider native 缓存" }
            check(tmp.renameTo(target)) { "无法保存 Spider native library" }
            check(target.setReadOnly()) { "无法锁定 Spider native library" }
            check(target.inputStream().use(::sha256).contentEquals(expectedHash)) {
                "Spider native library integrity mismatch"
            }
            target
        }
    }

    private fun sha256(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}

internal object SpiderFailureDetails {
    /** Reflection wrappers must not hide the actual failing native/JNI dependency. */
    fun message(error: Throwable): String {
        return SpiderRuntimeCompat.failure(error)
    }
}

/** FongMi's optional Init hook logs ordinary exceptions; runtime absence is fatal. */
internal object SpiderInitPolicy {
    fun blocking(error: Throwable): Throwable? {
        val fatal = generateSequence(error) {
            (it as? InvocationTargetException)?.targetException ?: it.cause
        }.take(8).firstOrNull { it is VirtualMachineError || it is ThreadDeath }
        return fatal ?: RuntimeModule.MissingException.find(error)
    }
}

internal class TvBoxSpiderWorker(private val context: Context) {
    private var active: Spider? = null
    private var activeKey: String? = null
    private var activeVipFlags: List<String> = emptyList()
    private var activeSiteHeaders: Map<String, String> = emptyMap()
    private var activeHttpHost: SpiderHttpSessionHost? = null
    private var activeLoopbackProxy: SpiderLoopbackProxy? = null

    private suspend fun <T> attempt(block: () -> T): SpiderResult<T> = try {
        withTimeoutOrNull(30_000) {
            SpiderResult.Ready(runInterruptible(Dispatchers.IO) { block() })
        } ?: run {
            close()
            SpiderResult.Failure("Spider 操作超时，请重新应用站点")
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (exception: Exception) {
        SpiderResult.Failure("Spider ${SpiderFailureDetails.message(exception)}")
    } catch (error: LinkageError) {
        SpiderResult.Failure("Spider ${SpiderFailureDetails.message(error)}")
    }

    suspend fun browse(site: TvBoxSite, config: TvBoxConfig.Single): SpiderResult<List<Film>> = attempt {
        val spiderApi = site.spiderApi ?: run {
            close()
            error("站点不是 Java csp_* 类型")
        }
        val jar = site.jar ?: config.spiderJar ?: run {
            close()
            error("配置没有 Spider JAR")
        }
        val key = SpiderRuntimeCompat.sessionKey(jar, site)
        val spider = if (key == activeKey) active!! else {
            close()
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.DOWNLOAD)
            val file = SpiderJarCache.obtain(context, jar)
            // One ephemeral cookie jar per active site; never shared across source changes.
            val httpHost = SpiderHttpSessionHost()
            Runtime.set(context.applicationContext)
            // An isolated Service cannot provide the foreground Activity from
            // the main process. Do not pretend the Application is an Activity.
            Runtime.setActivity { null }
            Runtime.setToast { android.util.Log.i("HerrinCatVod", "Plugin notification suppressed") }
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.NATIVE)
            // Extract ABI-matched JNI only to the DEX search directory. FongMi's
            // JarLoader does NOT eagerly System.load() an arbitrary JAR asset;
            // its own Init or Spider may decide when to load native code.
            val native = SpiderJarCache.nativeLibrary(context, file)
            val nativeFolder = SpiderRuntimeCompat.nativeDirectory(context, file)
                ?: native?.parentFile
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.LOADER)
            val loader = DexClassLoader(file.absolutePath, file.parentFile!!.absolutePath,
                nativeFolder?.absolutePath ?: file.parentFile!!.absolutePath,
                RuntimeModule.getSpiderClassLoader())
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.INITIALIZER)
            val initFailure = runCatching {
                val initializer = loader.loadClass("com.github.catvod.spider.Init")
                initializer.getMethod("init", Context::class.java).invoke(null, context)
            }.exceptionOrNull()
            if (initFailure != null && initFailure !is ClassNotFoundException) {
                // Match FongMi JarLoader.invokeInit(): optional bootstrap errors
                // are logged, except a missing required runtime must be surfaced.
                SpiderInitPolicy.blocking(initFailure)?.let { throw it }
                android.util.Log.w("HerrinCatVod", "Optional plugin initializer failed: ${initFailure.javaClass.simpleName}")
            }
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.CONSTRUCT)
            val spiderClass = loader.loadClass("com.github.catvod.spider.${spiderApi.removePrefix("csp_")}")
            val created = spiderClass.getDeclaredConstructor().newInstance() as Spider
            // CatVod Spider.getProxyUrl() needs a real, local listening port.
            // Start it inside :spider, scoped to the active site and process.
            val localProxy = SpiderLoopbackProxy(site.key) { params ->
                synchronized(created) { created.proxy(params) }
            }
            CatVodProxy.set(localProxy.port, localProxy.token)
            try {
                created.siteKey = site.key
                created.net = Net(httpHost, site.key)
                created.local = Local(site.key)
                SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.SPIDER_INIT)
                created.init(context, site.extension)
                created.also { active = it; activeKey = key; activeHttpHost = httpHost
                    activeLoopbackProxy = localProxy }
            } catch (error: Throwable) {
                localProxy.close()
                CatVodProxy.set(-1, "")
                SpiderRuntimeCompat.dispose(created)
                httpHost.clear()
                throw error
            }
        }
        // FongMi sends the root config's global VIP flags to playerContent.
        // Passing emptyList() prevented some plugins from generating the same
        // playable result as TVBox for an otherwise identical episode ID.
        activeVipFlags = config.vipFlags.take(32)
        activeSiteHeaders = site.headers
        SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.HOME)
        val films = SpiderCatalogBrowser.browse(site, spider::homeContent, spider::homeVideoContent) { id ->
            spider.categoryContent(id, "1", false, hashMapOf())
        }
        SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.READY)
        films
    }

    suspend fun details(film: Film): SpiderResult<Film> = attempt {
        SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.DETAILS)
        val spider = active ?: error("站点 Spider 尚未加载")
        val vodId = film.spiderVodId ?: error("该影片不属于 Spider 站点")
        SpiderVodCodec.detail(spider.detailContent(listOf(vodId)), film).also {
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.READY)
        }
    }

    suspend fun play(film: Film, episode: Int, line: String, allowHttp: Boolean): PlaybackResult {
        return when (val result = attempt {
            SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.PLAYER)
            val spider = active ?: error("站点 Spider 尚未加载")
            val selected = film.playbackEpisodes.firstOrNull { it.number == episode }
                ?.lines?.firstOrNull { it.id == line } ?: error("未找到 Spider 播放线路")
            SpiderVodCodec.player(spider.playerContent(selected.title, selected.uri, activeVipFlags),
                allowHttp, activeSiteHeaders).also {
                SpiderStartupTrace.mark(context, SpiderStartupTrace.Stage.READY)
            }
        }) {
            is SpiderResult.Ready -> result.value
            is SpiderResult.Failure -> PlaybackResult.Unavailable(result.message)
        }
    }

    fun close() {
        activeLoopbackProxy?.close()
        activeLoopbackProxy = null
        CatVodProxy.set(-1, "")
        SpiderRuntimeCompat.dispose(active)
        active = null
        activeKey = null
        activeVipFlags = emptyList()
        activeSiteHeaders = emptyMap()
        activeHttpHost?.clear()
        activeHttpHost = null
    }
}
