package tv.herrin.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Wire format for same-app IPC; only bounded JSON values cross Binder. */
internal object SpiderIpc {
    const val BROWSE = 1
    const val DETAILS = 2
    const val PLAY = 3
    const val MAX_RESPONSE_CHARS = 450_000

    fun site(site: TvBoxSite): JSONObject = JSONObject()
        .put("name", site.name).put("key", site.key)
        .put("spiderApi", site.spiderApi ?: "").put("ext", site.extension)
        .put("jar", site.jar ?: "")

    fun readSite(obj: JSONObject): TvBoxSite = TvBoxSite(
        name = obj.optString("name").take(64),
        needsAdapter = true,
        key = obj.optString("key").take(90),
        spiderApi = obj.optString("spiderApi").takeIf { it.startsWith("csp_") },
        extension = obj.optString("ext").take(64_000),
        jar = obj.optString("jar").takeIf { it.isNotBlank() })

    fun film(film: Film): JSONObject {
        val episodes = JSONArray()
        film.playbackEpisodes.take(99).forEach { ep ->
            val lines = JSONArray()
            ep.lines.take(8).forEach { line ->
                lines.put(JSONObject().put("id", line.id).put("title", line.title).put("uri", line.uri))
            }
            episodes.put(JSONObject().put("number", ep.number).put("title", ep.title).put("lines", lines))
        }
        return JSONObject().put("id", film.id).put("title", film.title)
            .put("subtitle", film.subtitle).put("category", film.category)
            .put("year", film.year).put("synopsis", film.synopsis)
            .put("artworkUrl", film.artworkUrl ?: "").put("episodes", film.episodes)
            .put("vodId", film.spiderVodId ?: "").put("playback", episodes)
    }

    fun readFilm(obj: JSONObject): Film {
        val array = obj.optJSONArray("playback") ?: JSONArray()
        val episodes = (0 until minOf(array.length(), 99)).mapNotNull { index ->
            val ep = array.optJSONObject(index) ?: return@mapNotNull null
            val linesArray = ep.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until minOf(linesArray.length(), 8)).mapNotNull { i ->
                val line = linesArray.optJSONObject(i) ?: return@mapNotNull null
                PlaybackLine(line.optString("id"), line.optString("title"), line.optString("uri"))
            }
            PlaybackEpisode(ep.optInt("number", 1), ep.optString("title"), lines)
        }
        return Film(
            id = obj.optString("id"), title = obj.optString("title"),
            subtitle = obj.optString("subtitle"), category = obj.optString("category"),
            year = obj.optString("year"), synopsis = obj.optString("synopsis"),
            artwork = R.drawable.photofilm_orbit,
            episodes = obj.optInt("episodes", 1),
            artworkUrl = obj.optString("artworkUrl").takeIf { it.isNotBlank() },
            playbackEpisodes = episodes,
            spiderVodId = obj.optString("vodId").takeIf { it.isNotBlank() })
    }

    fun error(message: String): JSONObject = JSONObject()
        .put("ok", false).put("message", message.take(250))

    fun playback(media: PlaybackMedia): JSONObject = JSONObject().put("ok", true)
        .put("uri", media.uri)
        .put("headers", JSONObject(media.headers))
        .put("subs", JSONArray().apply {
            media.subtitles.take(6).forEach { subtitle ->
                put(JSONObject().put("lang", subtitle.language)
                    .put("name", subtitle.title).put("url", subtitle.uri)
                    .put("format", "text/vtt"))
            }
        })

    fun readPlayback(response: JSONObject, allowHttp: Boolean): PlaybackResult {
        val url = TvBoxUrl.normalize(response.optString("uri"), allowHttp)
            ?: return PlaybackResult.Unavailable("Spider 播放地址无效")
        val headersObj = response.optJSONObject("headers") ?: JSONObject()
        val headers = buildMap {
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headersObj.optString(key)
                if (key.matches(Regex("[A-Za-z0-9-]{1,60}")) && value.length <= 2_048 &&
                    !value.contains('\r') && !value.contains('\n')) put(key, value)
            }
        }
        val subs = response.optJSONArray("subs") ?: JSONArray()
        val subtitles = (0 until minOf(6, subs.length())).mapNotNull { index ->
            val track = subs.optJSONObject(index) ?: return@mapNotNull null
            val trackUrl = TvBoxUrl.normalize(track.optString("url"), allowHttp)
                ?: return@mapNotNull null
            if (!trackUrl.substringBefore('?').lowercase().endsWith(".vtt") &&
                track.optString("format") != "text/vtt") return@mapNotNull null
            val lang = track.optString("lang").trim().lowercase()
            if (!lang.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) return@mapNotNull null
            AuthorizedSubtitle(lang, track.optString("name").take(64), trackUrl)
        }
        return PlaybackResult.Ready(PlaybackMedia(url, headers, subtitles))
    }
}

/**
 * Runs downloaded CatVod DEX/JNI in a separate app process so a fatal
 * native crash does not normally kill the TV UI process. This is NOT a
 * permission sandbox: the service still runs under the application's UID.
 */
class TvBoxSpiderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val worker by lazy { TvBoxSpiderWorker(applicationContext) }
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo
        val id = message.arg1
        val operation = message.what
        val payload = message.data.getString("payload").orEmpty()
        if (reply != null) {
            scope.launch {
                val response = lock.withLock {
                    try {
                        val request = JSONObject(payload)
                        when (operation) {
                            SpiderIpc.BROWSE -> {
                                val site = SpiderIpc.readSite(request.getJSONObject("site"))
                                val warehouse = TvBoxConfig.Single(
                                    sites = listOf(site), lives = emptyList(),
                                    hasExternalEngine = true,
                                    spiderJar = request.optString("defaultJar").takeIf { it.isNotBlank() })
                                when (val result = worker.browse(site, warehouse)) {
                                    is SpiderResult.Ready -> JSONObject().put("ok", true)
                                        .put("films", JSONArray().apply {
                                            result.value.take(80).forEach { put(SpiderIpc.film(it)) }
                                        })
                                    is SpiderResult.Failure -> SpiderIpc.error(result.message)
                                }
                            }
                            SpiderIpc.DETAILS -> {
                                when (val result = worker.details(SpiderIpc.readFilm(request.getJSONObject("film")))) {
                                    is SpiderResult.Ready -> JSONObject().put("ok", true)
                                        .put("film", SpiderIpc.film(result.value))
                                    is SpiderResult.Failure -> SpiderIpc.error(result.message)
                                }
                            }
                            SpiderIpc.PLAY -> {
                                val result = worker.play(SpiderIpc.readFilm(request.getJSONObject("film")),
                                    request.optInt("episode"), request.optString("line"),
                                    request.optBoolean("allowHttp"))
                                when (result) {
                                    is PlaybackResult.Ready -> SpiderIpc.playback(result.media)
                                    is PlaybackResult.Unavailable -> SpiderIpc.error(result.reason)
                                }
                            }
                            else -> SpiderIpc.error("不支持的插件操作")
                        }
                    } catch (e: Exception) {
                        Log.e("HerrinSpiderWorker", "Spider operation failed", e)
                        SpiderIpc.error(SpiderFailureDetails.message(e))
                    } catch (e: LinkageError) {
                        Log.e("HerrinSpiderWorker", "Spider linkage failed", e)
                        SpiderIpc.error(SpiderFailureDetails.message(e))
                    }
                }
                val bounded = if (response.toString().length <= SpiderIpc.MAX_RESPONSE_CHARS) response
                    else SpiderIpc.error("Spider 返回内容超过进程通信限制")
                val responseMessage = Message.obtain(null, operation).apply {
                    arg1 = id
                    data = Bundle().apply { putString("payload", bounded.toString()) }
                }
                try { reply.send(responseMessage) }
                catch (e: android.os.RemoteException) {
                    Log.w("HerrinSpiderWorker", "Caller went away", e)
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        worker.close()
        scope.cancel()
        super.onDestroy()
    }
}

/** Main-process facade: a plugin crash becomes a failure, not a TV UI crash. */
internal class TvBoxSpiderBridge(context: Context) {
    private val app = context.applicationContext
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private val waitingForBind = mutableListOf<CompletableDeferred<Messenger>>()
    private val nextId = AtomicInteger()
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false

    private val responses = Messenger(Handler(Looper.getMainLooper()) { message ->
        val deferred = pending.remove(message.arg1)
        deferred?.complete(message.data.getString("payload").orEmpty())
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                waitingForBind.forEach { it.completeExceptionally(IllegalStateException("Spider 服务未提供 Binder")) }
                waitingForBind.clear()
                return
            }
            val messenger = Messenger(binder)
            remote = messenger
            waitingForBind.forEach { it.complete(messenger) }
            waitingForBind.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            val error = "Spider 子进程异常退出；主界面仍可使用"
            waitingForBind.forEach { it.completeExceptionally(IllegalStateException(error)) }
            waitingForBind.clear()
            pending.values.forEach { it.complete(SpiderIpc.error(error).toString()) }
            pending.clear()
        }
    }

    private suspend fun service(): Messenger {
        remote?.let { return it }
        check(!closed) { "Spider 执行服务已关闭" }
        val waiter = CompletableDeferred<Messenger>()
        waitingForBind += waiter
        if (!bound) {
            val connected = app.bindService(
                Intent(app, TvBoxSpiderService::class.java),
                connection, Context.BIND_AUTO_CREATE)
            check(connected) { "系统拒绝启动 Spider 子进程" }
            bound = true
        }
        return withTimeout(10_000) { waiter.await() }
    }

    private suspend fun request(operation: Int, payload: JSONObject): SpiderResult<JSONObject> = try {
        val response = withContext(Dispatchers.Main.immediate) {
            val server = service()
            val id = nextId.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            try {
                server.send(Message.obtain(null, operation).apply {
                    arg1 = id
                    replyTo = responses
                    data = Bundle().apply { putString("payload", payload.toString()) }
                })
                withTimeout(37_000) { deferred.await() }
            } finally {
                pending.remove(id)
            }
        }
        val result = JSONObject(response)
        if (result.optBoolean("ok")) SpiderResult.Ready(result)
        else SpiderResult.Failure(result.optString("message").ifBlank { "Spider 返回错误" })
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        SpiderResult.Failure("Spider 服务错误：" + SpiderFailureDetails.message(error))
    }

    suspend fun browse(site: TvBoxSite, config: TvBoxConfig.Single): SpiderResult<List<Film>> {
        val payload = JSONObject().put("site", SpiderIpc.site(site))
            .put("defaultJar", config.spiderJar ?: "")
        return when (val response = request(SpiderIpc.BROWSE, payload)) {
            is SpiderResult.Failure -> response
            is SpiderResult.Ready -> {
                try {
                    val array = response.value.getJSONArray("films")
                    SpiderResult.Ready((0 until array.length()).map { SpiderIpc.readFilm(array.getJSONObject(it)) })
                } catch (error: Exception) {
                    SpiderResult.Failure("Spider 影片数据无效：" + error.message.orEmpty())
                }
            }
        }
    }

    suspend fun details(film: Film): SpiderResult<Film> =
        when (val response = request(SpiderIpc.DETAILS,
            JSONObject().put("film", SpiderIpc.film(film)))) {
            is SpiderResult.Failure -> response
            is SpiderResult.Ready -> try {
                SpiderResult.Ready(SpiderIpc.readFilm(response.value.getJSONObject("film")))
            } catch (error: Exception) {
                SpiderResult.Failure("Spider 详情无效：" + error.message.orEmpty())
            }
        }

    suspend fun play(film: Film, episode: Int, line: String, allowHttp: Boolean): PlaybackResult {
        val payload = JSONObject().put("film", SpiderIpc.film(film))
            .put("episode", episode).put("line", line).put("allowHttp", allowHttp)
        return when (val response = request(SpiderIpc.PLAY, payload)) {
            is SpiderResult.Failure -> PlaybackResult.Unavailable(response.message)
            is SpiderResult.Ready -> try {
                SpiderIpc.readPlayback(response.value, allowHttp)
            } catch (error: Exception) {
                PlaybackResult.Unavailable("Spider 播放信息无效：" + error.message.orEmpty())
            }
        }
    }

    fun close() {
        Handler(Looper.getMainLooper()).post {
            if (closed) return@post
            closed = true
            val error = SpiderIpc.error("Spider 会话已结束").toString()
            pending.values.forEach { it.complete(error) }
            pending.clear()
            waitingForBind.forEach { it.completeExceptionally(IllegalStateException("Spider 会话已结束")) }
            waitingForBind.clear()
            if (bound) runCatching { app.unbindService(connection) }
            bound = false
            remote = null
            closed = false // A later "应用影视源" starts a new bound session.
        }
    }
}
