package tv.herrin.app

import com.github.catvod.Runtime as CatVodRuntime

import android.app.ActivityManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Wire format for same-app IPC; only bounded JSON values cross Binder. */
internal object SpiderIpc {
    const val BROWSE = 1
    const val DETAILS = 2
    const val PLAY = 3
    const val AUTH_SHOW = 10
    const val AUTH_ACTION = 11
    const val AUTH_CLOSE = 12
    const val MAX_RESPONSE_CHARS = 450_000
    const val MAX_REQUEST_CHARS = 400_000

    fun site(site: TvBoxSite): JSONObject = JSONObject()
        .put("name", site.name).put("key", site.key)
        .put("spiderApi", site.spiderApi ?: "").put("ext", site.extension)
        .put("jar", site.jar ?: "")
        .put("header", JSONObject(site.headers))

    fun readSite(obj: JSONObject): TvBoxSite = TvBoxSite(
        name = obj.optString("name").take(64),
        needsAdapter = true,
        key = obj.optString("key").take(90),
        spiderApi = obj.optString("spiderApi").takeIf { it.startsWith("csp_") },
        extension = obj.optString("ext").take(64_000),
        jar = obj.optString("jar").takeIf { it.isNotBlank() },
        headers = TvBoxHeaderCodec.decode(obj.optJSONObject("header")))

    fun film(film: Film): JSONObject {
        val episodes = JSONArray()
        var episodeChars = 0
        for (ep in film.playbackEpisodes.take(300)) {
            val lines = JSONArray()
            ep.lines.take(12).forEach { line ->
                lines.put(JSONObject().put("id", line.id).put("title", line.title).put("uri", line.uri))
            }
            val encoded = JSONObject().put("number", ep.number).put("title", ep.title).put("lines", lines)
            val length = encoded.toString().length
            // Binder replies have a bounded payload. Keep full normal-length
            // seasons (not just the first 99) without dropping the whole detail
            // response when one provider supplies exceptionally long IDs.
            if (episodeChars + length > 350_000) break
            episodeChars += length
            episodes.put(encoded)
        }
        return JSONObject().put("id", film.id).put("title", film.title)
            .put("subtitle", film.subtitle).put("category", film.category)
            .put("year", film.year).put("synopsis", film.synopsis)
            .put("artworkUrl", film.artworkUrl ?: "")
            .put("backdropUrl", film.backdropUrl ?: "").put("episodes", film.episodes)
            .put("vodId", film.spiderVodId ?: "").put("playback", episodes)
    }

    fun readFilm(obj: JSONObject): Film {
        val array = obj.optJSONArray("playback") ?: JSONArray()
        val episodes = (0 until minOf(array.length(), 300)).mapNotNull { index ->
            val ep = array.optJSONObject(index) ?: return@mapNotNull null
            val linesArray = ep.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until minOf(linesArray.length(), 12)).mapNotNull { i ->
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
            backdropUrl = obj.optString("backdropUrl").takeIf { it.isNotBlank() },
            playbackEpisodes = episodes,
            spiderVodId = obj.optString("vodId").takeIf { it.isNotBlank() })
    }

    fun error(message: String): JSONObject = JSONObject()
        .put("ok", false).put("message", message.take(250))

    fun playback(media: PlaybackMedia): JSONObject = JSONObject().put("ok", true)
        .put("uri", media.uri)
        .put("alternates", JSONArray(media.alternateUris.take(7)))
        .put("allowHttpRedirects", media.allowHttpRedirects)
        .put("declaredMime", media.declaredMime ?: "")
        .put("danmaku", DanmakuProtocol.encodeSources(media.danmakuSources))
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
        val alternatives = response.optJSONArray("alternates") ?: JSONArray()
        val urls = (0 until minOf(alternatives.length(), 7)).mapNotNull { index ->
            TvBoxUrl.normalize(alternatives.optString(index), allowHttp)
        }.filter { it != url }.distinct()
        return PlaybackResult.Ready(PlaybackMedia(url, headers, subtitles, urls,
            response.optBoolean("allowHttpRedirects") && allowHttp,
            declaredMime = PlaybackFormatRetry.declaredMime(response.optString("declaredMime")),
            danmakuSources = DanmakuProtocol.decodeSources(response.opt("danmaku"))))
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
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val worker by lazy { TvBoxSpiderWorker(applicationContext) }
    @Volatile private var authCaller: Pair<Messenger, Int>? = null
    private val authorization = SpiderAuthorizationSession { dialogId, payload ->
        authCaller?.let { (caller, operationId) ->
            runCatching { caller.send(Message.obtain(null, if (payload == null) SpiderIpc.AUTH_CLOSE else SpiderIpc.AUTH_SHOW).apply {
                arg1 = operationId; arg2 = dialogId
                replyTo = incoming
                data = Bundle().apply { if (payload != null) putString("payload", payload) }
            }) }
        }
    }
    private val incoming: Messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SpiderIpc.AUTH_ACTION) {
            if (authCaller?.second == message.arg1) authorization.action(message.arg2, message.data.getString("button"))
            return@Handler true
        }
        val reply = message.replyTo
        val id = message.arg1
        val operation = message.what
        val payload = message.data.getString("payload").orEmpty()
        if (reply != null) {
            scope.launch {
                val response = lock.withLock {
                    authCaller = reply to id
                    // Cancellation cannot stop a JNI call that ignores interruption.
                    // This watchdog belongs solely to the manifest's :spider process.
                    val terminate = watchdog.schedule({
                        // If this fires, it is a HOST watchdog timeout, not proof
                        // of a native SIGSEGV. Persist only this fixed stage string.
                        SpiderStartupTrace.mark(applicationContext, SpiderStartupTrace.Stage.WATCHDOG)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 160, TimeUnit.SECONDS)
                    try {
                        require(payload.length <= SpiderIpc.MAX_REQUEST_CHARS) { "Spider 请求超过进程通信限制" }
                        val request = JSONObject(payload)
                        when (operation) {
                            SpiderIpc.BROWSE -> {
                                val site = SpiderIpc.readSite(request.getJSONObject("site"))
                                val warehouse = TvBoxConfig.Single(
                                    sites = listOf(site), lives = emptyList(),
                                    hasExternalEngine = true,
                                    spiderJar = request.optString("defaultJar").takeIf { it.isNotBlank() },
                                    vipFlags = request.optJSONArray("vipFlags")?.let { array ->
                                        (0 until minOf(array.length(), 32)).mapNotNull { index ->
                                            array.optString(index).trim().take(80).takeIf { it.isNotBlank() }
                                        }
                                    }.orEmpty())
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
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        Log.e("HerrinSpiderWorker", "Spider operation failed: ${e.javaClass.simpleName}")
                        SpiderIpc.error(SpiderFailureDetails.message(e))
                    } catch (e: LinkageError) {
                        Log.e("HerrinSpiderWorker", "Spider linkage failed: ${e.javaClass.simpleName}")
                        SpiderIpc.error(SpiderFailureDetails.message(e))
                    } finally {
                        authorization.cancel()
                        authCaller = null
                        terminate.cancel(false)
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
                    Log.w("HerrinSpiderWorker", "Caller went away")
                }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        val process = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        check(process == "$packageName:spider") { "Spider must run in its dedicated process" }
        CatVodRuntime.setDialog { options, callback ->
            if (authCaller == null) java.util.concurrent.CompletableFuture<String>().apply {
                completeExceptionally(IllegalStateException("No active authorization operation"))
            } else try {
                authorization.show(options, callback).also { result ->
                    val deadline = watchdog.schedule({ result.complete(null) },
                        SpiderAuthorizationProtocol.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    result.whenComplete { _, _ -> deadline.cancel(false) }
                }
            } catch (_: Exception) {
                java.util.concurrent.CompletableFuture<String>().apply {
                    completeExceptionally(IllegalArgumentException("Unsupported authorization dialog"))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        authorization.cancel()
        CatVodRuntime.setDialog(null)
        scope.cancel()
        // A plugin's destroy can also hang; the deadline runs off the main looper.
        val terminate = watchdog.schedule({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 5, TimeUnit.SECONDS)
        try { worker.close() }
        finally {
            terminate.cancel(false)
            // Keep an in-flight operation's deadline alive if cancellation was
            // ignored in JNI. Normal cancellation completes and stops the timer.
            scope.coroutineContext[Job]?.invokeOnCompletion { watchdog.shutdownNow() }
            super.onDestroy()
        }
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
    private val requests = Mutex()
    @Volatile private var generation = 0
    @Volatile private var hydratedGeneration = -1
    @Volatile private var lastBrowse: String? = null

    private val responses = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SpiderIpc.AUTH_SHOW || message.what == SpiderIpc.AUTH_CLOSE) {
            val server = message.replyTo
            val respond: (String?) -> Unit = { button ->
                runCatching { server?.send(Message.obtain(null, SpiderIpc.AUTH_ACTION).apply {
                    arg1 = message.arg1; arg2 = message.arg2
                    data = Bundle().apply { if (button != null) putString("button", button) }
                }) }
                Unit
            }
            if (pending.containsKey(message.arg1)) SpiderAuthorizationUi.receive(this,
                message.arg1, message.arg2,
                if (message.what == SpiderIpc.AUTH_CLOSE) null else message.data.getString("payload"), respond)
            else respond(null)
            return@Handler true
        }
        val deferred = pending.remove(message.arg1)
        deferred?.complete(message.data.getString("payload").orEmpty())
        true
    })
    private var connection: ServiceConnection? = null

    private fun newConnection(epoch: Int) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (epoch != generation || connection !== this) return
            if (binder == null) {
                invalidate("Spider 服务未提供 Binder")
                return
            }
            val messenger = Messenger(binder)
            remote = messenger
            waitingForBind.forEach { it.complete(messenger) }
            waitingForBind.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (epoch != generation || connection !== this) return
            // Invalidate IMMEDIATELY so the next user action can bind a fresh
            // service; never hold dead Messenger references while waiting for
            // the OS to publish a diagnostic record.
            val stage = SpiderStartupTrace.recent(app)
            val cause = SpiderExitDiagnostics.recent(app)
            invalidate("Spider 子进程异常退出" +
                (stage?.let { "，最后阶段：$it" } ?: "") +
                (cause?.let { "，系统原因：$it" } ?: "") +
                "；可换一条直链或重新应用站点")
        }

        override fun onBindingDied(name: ComponentName?) {
            if (epoch != generation || connection !== this) return
            invalidate("Spider 服务连接失效，请重试")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (epoch != generation || connection !== this) return
            invalidate("Spider 服务未提供 Binder")
        }
    }

    /** Main-thread only: release dead registrations and reject every outstanding caller. */
    private fun invalidate(reason: String) {
        SpiderAuthorizationUi.clear(this)
        generation++
        hydratedGeneration = -1
        remote = null
        val registration = connection
        connection = null
        if (bound && registration != null) runCatching { app.unbindService(registration) }
        bound = false
        waitingForBind.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        waitingForBind.clear()
        pending.values.forEach { it.complete(SpiderIpc.error(reason).toString()) }
        pending.clear()
    }

    private suspend fun service(): Messenger {
        check(!closed) { "Spider 执行服务已关闭" }
        remote?.let { return it }
        val waiter = CompletableDeferred<Messenger>()
        waitingForBind += waiter
        try {
            if (!bound) {
                val registration = newConnection(generation)
                connection = registration
                bound = app.bindService(Intent(app, TvBoxSpiderService::class.java),
                    registration, Context.BIND_AUTO_CREATE)
                check(bound) { "系统拒绝启动 Spider 子进程" }
            }
            return withTimeout(10_000) { waiter.await() }
        } catch (timeout: TimeoutCancellationException) {
            invalidate("Spider 服务启动超时，请重试")
            throw timeout
        } finally {
            waitingForBind.remove(waiter)
            waiter.cancel()
        }
    }

    // Serialize source changes and media calls. After a crash, rebuild exactly the
    // last successful site once before a detail/play operation; never loop retries.
    private suspend fun request(operation: Int, payload: JSONObject): SpiderResult<JSONObject> =
        requests.withLock {
            val startedGeneration = generation
            val remembered = lastBrowse
            if (operation != SpiderIpc.BROWSE && remembered == null)
                return@withLock SpiderResult.Failure("Spider 站点尚未就绪，请重新应用站点")
            if (operation != SpiderIpc.BROWSE && remembered != null && hydratedGeneration != generation) {
                when (val restored = sendRequest(SpiderIpc.BROWSE, JSONObject(remembered))) {
                    is SpiderResult.Failure -> return@withLock restored
                    is SpiderResult.Ready -> {
                        if (startedGeneration != generation)
                            return@withLock SpiderResult.Failure("Spider 会话已变化，请重试")
                        hydratedGeneration = generation
                    }
                }
            }
            if (operation == SpiderIpc.BROWSE) {
                lastBrowse = null
                hydratedGeneration = -1
            }
            val result = sendRequest(operation, payload)
            if (startedGeneration != generation && result is SpiderResult.Ready)
                return@withLock SpiderResult.Failure("Spider 会话已变化，请重试")
            if (operation == SpiderIpc.BROWSE) {
                if (result is SpiderResult.Ready) {
                    lastBrowse = payload.toString()
                    hydratedGeneration = generation
                } else {
                    // A failed source switch cannot resurrect the previous source.
                    lastBrowse = null
                    hydratedGeneration = -1
                }
            }
            result
        }

    private suspend fun sendRequest(operation: Int, payload: JSONObject): SpiderResult<JSONObject> = try {
        val response = withContext(Dispatchers.Main.immediate) {
            val encoded = payload.toString()
            require(encoded.length <= SpiderIpc.MAX_REQUEST_CHARS) { "Spider 请求超过进程通信限制" }
            val server = service()
            val id = nextId.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            try {
                server.send(Message.obtain(null, operation).apply {
                    arg1 = id
                    replyTo = responses
                    data = Bundle().apply { putString("payload", encoded) }
                })
                withTimeout(165_000) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                invalidate("Spider 操作超时，请重新应用站点")
                throw timeout
            } catch (error: android.os.RemoteException) {
                invalidate("Spider 服务已断开，请重试")
                throw error
            } finally {
                pending.remove(id)
                SpiderAuthorizationUi.clear(this@TvBoxSpiderBridge)
            }
        }
        require(response.length <= SpiderIpc.MAX_RESPONSE_CHARS) { "Spider 返回内容超过进程通信限制" }
        val result = JSONObject(response)
        if (result.optBoolean("ok")) SpiderResult.Ready(result)
        else SpiderResult.Failure(result.optString("message").ifBlank { "Spider 返回错误" })
    } catch (timeout: TimeoutCancellationException) {
        SpiderResult.Failure("Spider 服务响应超时，请重新应用站点")
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        SpiderResult.Failure("Spider 服务错误：" + SpiderFailureDetails.message(error))
    }

    suspend fun browse(site: TvBoxSite, config: TvBoxConfig.Single): SpiderResult<List<Film>> {
        withContext(Dispatchers.Main.immediate) { SpiderAuthorizationUi.clear(this@TvBoxSpiderBridge) }
        val payload = JSONObject().put("site", SpiderIpc.site(site))
            .put("defaultJar", config.spiderJar ?: "")
            .put("vipFlags", JSONArray(config.vipFlags.take(32)))
        return when (val response = request(SpiderIpc.BROWSE, payload)) {
            is SpiderResult.Failure -> response
            is SpiderResult.Ready -> {
                try {
                    val array = response.value.getJSONArray("films")
                    SpiderResult.Ready((0 until array.length()).map { SpiderIpc.readFilm(array.getJSONObject(it)) })
                } catch (error: Exception) {
                    SpiderResult.Failure("Spider 影片数据无效：" + SpiderFailureDetails.message(error))
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
                SpiderResult.Failure("Spider 详情无效：" + SpiderFailureDetails.message(error))
            }
        }

    suspend fun play(film: Film, episode: Int, line: String, allowHttp: Boolean): PlaybackResult {
        val payload = JSONObject().put("film", SpiderIpc.film(film))
            .put("episode", episode).put("line", line).put("allowHttp", allowHttp)
        return when (val response = request(SpiderIpc.PLAY, payload)) {
            is SpiderResult.Failure -> {
                // The source has ALREADY supplied a direct HTTP(S) media URL.
                // If the Java Spider crashed resolving it again, try that exact
                // URL; never infer a URL from an opaque episode identifier.
                val appliedSite = if (SpiderDirectPlayback.eligibleFailure(response.message)) {
                    runCatching {
                        lastBrowse?.let { SpiderIpc.readSite(JSONObject(it).getJSONObject("site")) }
                    }.getOrNull()
                } else null
                appliedSite?.let {
                    SpiderDirectPlayback.resolve(film, episode, line, it, allowHttp)
                } ?: PlaybackResult.Unavailable(response.message)
            }
            is SpiderResult.Ready -> try {
                SpiderIpc.readPlayback(response.value, allowHttp)
            } catch (error: Exception) {
                PlaybackResult.Unavailable("Spider 播放信息无效：" + SpiderFailureDetails.message(error))
            }
        }
    }

    fun close() {
        Handler(Looper.getMainLooper()).post {
            if (closed) return@post
            closed = true
            lastBrowse = null
            invalidate("Spider 会话已结束")
            closed = false // A later "应用影视源" starts a new bound session.
        }
    }
}
