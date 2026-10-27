package tv.herrin.app

import android.app.Activity
import android.content.Context
import com.fongmi.quickjs.host.Host
import com.github.catvod.Runtime
import com.github.catvod.crawler.Spider
import com.github.catvod.net.Net
import com.github.catvod.utils.Local
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * GPL-3.0 CatVod adapter. The SDK source is vendored from FongMi/TV@123cc08.
 * User-supplied executable JARs are NEVER fetched or invoked without session opt-in.
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
                category = vod.optString("type_name").take(40).ifBlank { "影视" },
                year = vod.optString("vod_year").take(10),
                synopsis = vod.optString("vod_content").replace(Regex("<[^>]*>"), " ").take(1200),
                artwork = R.drawable.photofilm_orbit,
                artworkUrl = TvBoxUrl.normalize(vod.optString("vod_pic"), allowHttp = true),
                spiderVodId = id)
        }
    }

    fun detail(json: String, film: Film): Film {
        val item = JSONObject(json).optJSONArray("list")?.optJSONObject(0)
            ?: error("Spider 详情缺少 list[0]")
        val flags = item.optString("vod_play_from").split("$$$").take(8)
        val episodes = linkedMapOf<Int, MutableList<PlaybackLine>>()
        item.optString("vod_play_url").split("$$$").take(8).forEachIndexed { lineIndex, playlist ->
            val flag = flags.getOrNull(lineIndex).orEmpty().ifBlank { "线路 ${lineIndex + 1}" }.take(80)
            playlist.split('#').take(99).forEachIndexed { index, entry ->
                val opaqueId = entry.substringAfter('$', if ('$' in entry) "" else entry).trim()
                if (opaqueId.isNotEmpty() && opaqueId.length <= 2_048) {
                    episodes.getOrPut(index + 1) { mutableListOf() }.add(
                        PlaybackLine("spider-$lineIndex", flag, opaqueId))
                }
            }
        }
        val playback = episodes.map { (number, lines) ->
            PlaybackEpisode(number, "第 $number 集", lines)
        }
        return film.copy(
            synopsis = item.optString("vod_content").replace(Regex("<[^>]*>"), " ").take(1200)
                .ifBlank { film.synopsis },
            episodes = maxOf(1, playback.size), playbackEpisodes = playback)
    }

    fun player(json: String, allowHttp: Boolean): PlaybackResult {
        val data = JSONObject(json)
        if (data.optInt("parse", 0) != 0)
            return PlaybackResult.Unavailable("此线路返回网页解析请求，尚不能交给 Media3 直接播放")
        val uri = TvBoxUrl.normalize(data.optString("url"), allowHttp)
            ?: return PlaybackResult.Unavailable("Spider 没有返回本次授权的 HTTP(S) 直链")
        val header = data.optJSONObject("header") ?: data.optJSONObject("headers")
        val headers = buildMap {
            if (header != null) {
                val names = header.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val value = header.optString(name)
                    if (name.matches(Regex("[A-Za-z0-9-]{1,60}")) &&
                        value.length <= 2_048 && !value.contains('\n') && !value.contains('\r'))
                        put(name, value)
                }
            }
        }
        return PlaybackResult.Ready(PlaybackMedia(uri, headers))
    }
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
        if (destination.isFile && validJar(destination, md5)) return destination
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
}

internal class TvBoxSpiderBridge(private val context: Context) {
    private var active: Spider? = null
    private var activeKey: String? = null

    private suspend fun <T> attempt(block: () -> T): SpiderResult<T> = try {
        SpiderResult.Ready(withTimeout(30_000) { runInterruptible(Dispatchers.IO) { block() } })
    } catch (exception: Exception) {
        SpiderResult.Failure("Spider ${exception.javaClass.simpleName}：${exception.message.orEmpty().take(140)}")
    } catch (error: LinkageError) {
        SpiderResult.Failure("Spider 缺少兼容依赖：${error.message.orEmpty().take(140)}")
    }

    suspend fun browse(site: TvBoxSite, config: TvBoxConfig.Single): SpiderResult<List<Film>> = attempt {
        val spiderApi = requireNotNull(site.spiderApi) { "站点不是 Java csp_* 类型" }
        val jar = site.jar ?: config.spiderJar ?: error("配置没有 Spider JAR")
        val key = "${jar.hashCode()}:${site.key}"
        val spider = if (key == activeKey) active!! else {
            close()
            val file = SpiderJarCache.obtain(context, jar)
            Runtime.set(context.applicationContext)
            Runtime.setActivity { context as? Activity }
            Runtime.setToast { android.util.Log.i("HerrinCatVod", it.take(150)) }
            val loader = DexClassLoader(file.absolutePath, context.codeCacheDir.absolutePath,
                context.applicationInfo.nativeLibraryDir, Spider::class.java.classLoader)
            runCatching {
                val initializer = loader.loadClass("com.github.catvod.spider.Init")
                initializer.getMethod("init", Context::class.java).invoke(null, context)
            }.onFailure { if (it !is ClassNotFoundException) throw it }
            val spiderClass = loader.loadClass("com.github.catvod.spider.${spiderApi.removePrefix("csp_")}")
            (spiderClass.getDeclaredConstructor().newInstance() as Spider).apply {
                siteKey = site.key
                net = Net(Host.NONE, site.key)
                local = Local(site.key)
                init(context, site.extension)
            }.also { active = it; activeKey = key }
        }
        val home = spider.homeContent(true)
        // Some FongMi plugins deliberately return no home JSON and only expose
        // homeVideoContent or a category list. Do not abort before those fallbacks.
        fun tryListing(payload: String): List<Film> = runCatching {
            SpiderVodCodec.listing(payload, site)
        }.getOrDefault(emptyList())
        val films = tryListing(home).ifEmpty { tryListing(spider.homeVideoContent()) }
        if (films.isEmpty()) {
            val classes = runCatching { JSONObject(home).optJSONArray("class") }.getOrNull()
            val first = classes?.optJSONObject(0)?.optString("type_id").orEmpty()
            if (first.isBlank()) error("Spider 首页没有可浏览的影片或分类")
            tryListing(spider.categoryContent(first, "1", false, hashMapOf()))
                .ifEmpty { error("Spider 分类没有返回影片") }
        } else films
    }

    suspend fun details(film: Film): SpiderResult<Film> = attempt {
        val spider = active ?: error("站点 Spider 尚未加载")
        val vodId = film.spiderVodId ?: error("该影片不属于 Spider 站点")
        SpiderVodCodec.detail(spider.detailContent(listOf(vodId)), film)
    }

    suspend fun play(film: Film, episode: Int, line: String, allowHttp: Boolean): PlaybackResult {
        return when (val result = attempt {
            val spider = active ?: error("站点 Spider 尚未加载")
            val selected = film.playbackEpisodes.firstOrNull { it.number == episode }
                ?.lines?.firstOrNull { it.id == line } ?: error("未找到 Spider 播放线路")
            SpiderVodCodec.player(spider.playerContent(selected.title, selected.uri, emptyList()), allowHttp)
        }) {
            is SpiderResult.Ready -> result.value
            is SpiderResult.Failure -> PlaybackResult.Unavailable(result.message)
        }
    }

    fun close() {
        runCatching { active?.destroy() }
        runCatching { active?.net?.close() }
        runCatching { active?.dialog?.close() }
        runCatching { active?.js?.close() }
        active = null
        activeKey = null
    }
}
