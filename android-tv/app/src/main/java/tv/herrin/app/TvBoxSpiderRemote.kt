package tv.herrin.app

import com.github.catvod.Runtime as CatVodRuntime
import tv.herrin.app.nativeplayback.NativeFongMiRoute
import tv.herrin.app.nativeplayback.NativeFongMiProxy

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Wire format for same-app IPC; only bounded JSON values cross Binder. */
internal object SpiderIpc {
    val requestIds=AtomicInteger()
    const val BROWSE = 1
    const val DETAILS = 2
    const val SEARCH = 4
    const val PLAY = 3
    const val PROXY_SELF_TEST = 20
    const val RELEASE_PLAYBACK = 21
    const val CANCEL_REQUEST = 22
    const val AUTH_SHOW = 10
    const val AUTH_ACTION = 11
    const val AUTH_CLOSE = 12
    const val MAX_RESPONSE_CHARS = 450_000
    const val MAX_REQUEST_CHARS = 400_000

    fun site(site: TvBoxSite): JSONObject = JSONObject()
        .put("name", site.name).put("key", site.key)
        .put("spiderApi", site.spiderApi ?: "").put("scriptApi", site.scriptApi ?: "")
        .put("ext", site.extension)
        .put("jar", site.jar ?: "").put("click", site.click)
        .put("header", JSONObject(site.headers))

    fun readSite(obj: JSONObject): TvBoxSite = TvBoxSite(
        name = obj.optString("name").take(64),
        needsAdapter = true,
        key = obj.optString("key").take(90),
        spiderApi = obj.optString("spiderApi").takeIf { it.startsWith("csp_") },
        scriptApi = obj.optString("scriptApi").takeIf { api ->
            (api.startsWith("node:") && api.length <= 256) ||
                (TvBoxUrl.normalize(api, true) != null && (
                    api.substringBefore('?').endsWith(".js", true) ||
                    api.substringBefore('?').endsWith(".py", true)))
        },
        extension = obj.optString("ext").take(64_000),
        click = obj.optString("click").take(4096),
        jar = obj.optString("jar").takeIf { it.isNotBlank() },
        headers = TvBoxHeaderCodec.decode(obj.optJSONObject("header")))

    fun jsonParsers(parsers: List<TvBoxJsonParse>): JSONArray = JSONArray().apply {
        parsers.take(16).forEach { parser ->
            put(JSONObject().put("name", parser.name).put("prefix", parser.prefix)
                .put("flags", JSONArray(parser.flags.take(32)))
                .put("headers", JSONObject(parser.headers)))
        }
    }

    fun readJsonParsers(values: JSONArray?): List<TvBoxJsonParse> {
        if (values == null) return emptyList()
        return (0 until minOf(values.length(), 16)).mapNotNull { index ->
            val obj = values.optJSONObject(index) ?: return@mapNotNull null
            val prefix = TvBoxUrl.normalize(obj.optString("prefix"), true)
                ?: return@mapNotNull null
            val array = obj.optJSONArray("flags") ?: JSONArray()
            val flags = (0 until minOf(array.length(), 32)).mapNotNull {
                array.optString(it).trim().take(80).takeIf(String::isNotBlank)
            }
            TvBoxJsonParse(obj.optString("name").take(64), prefix,
                flags, TvBoxHeaderCodec.decode(obj.optJSONObject("headers")))
        }
    }

    fun playbackParsers(parsers: List<TvBoxPlaybackParser>): JSONArray = JSONArray().apply {
        parsers.take(24).forEach { parser ->
            put(JSONObject().put("name", parser.name).put("type", parser.type)
                .put("url", parser.url).put("flags", JSONArray(parser.flags.take(32)))
                .put("headers", JSONObject(parser.headers))
                .put("click", parser.click))
        }
    }

    fun readPlaybackParsers(array: JSONArray?): List<TvBoxPlaybackParser> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), 24)).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val type = obj.optInt("type", -1)
            if (type !in 0..4) return@mapNotNull null
            val raw = obj.optString("url").take(2048)
            val url = if (type in 0..1) TvBoxUrl.normalize(raw, true)
                ?: return@mapNotNull null else raw
            val flags = obj.optJSONArray("flags") ?: JSONArray()
            TvBoxPlaybackParser(obj.optString("name").take(64), type, url,
                (0 until minOf(flags.length(), 32)).mapNotNull {
                    flags.optString(it).trim().take(80).takeIf(String::isNotBlank)
                }, TvBoxHeaderCodec.decode(obj.optJSONObject("headers")),
                obj.optString("click").take(4_096))
        }
    }

    fun film(film: Film): JSONObject {
        val episodes = JSONArray()
        var episodeChars = 0
        for (ep in film.playbackEpisodes.take(300)) {
            val lines = JSONArray()
            ep.lines.take(12).forEach { line ->
                lines.put(JSONObject().put("id", line.id).put("title", line.title).put("uri", line.uri)
                    .put("proof", line.spiderProof?.value))
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
            .put("origin", film.spiderOrigin?.let {
                JSONObject().put("site", it.site).put("runtime", it.runtime).put("proof", it.proof)
            })
    }

    fun readFilm(obj: JSONObject): Film {
        val array = obj.optJSONArray("playback") ?: JSONArray()
        val episodes = (0 until minOf(array.length(), 300)).mapNotNull { index ->
            val ep = array.optJSONObject(index) ?: return@mapNotNull null
            val linesArray = ep.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until minOf(linesArray.length(), 12)).mapNotNull { i ->
                val line = linesArray.optJSONObject(i) ?: return@mapNotNull null
                PlaybackLine(line.optString("id"), line.optString("title"), line.optString("uri"),
                    spiderProof = line.optString("proof").takeIf { it.length == 64 }?.let(::SpiderRouteProof))
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
            spiderVodId = obj.optString("vodId").takeIf { it.isNotBlank() },
            spiderOrigin = obj.optJSONObject("origin")?.let {
                SpiderFilmOrigin(it.optString("site").take(128), it.optString("runtime").take(64),
                    it.optString("proof").take(64))
            })
    }

    fun error(message: String): JSONObject = JSONObject()
        .put("ok", false).put("message", message.take(250))

    fun playback(media: PlaybackMedia): JSONObject = JSONObject().put("ok", true)
        .put("uri", media.uri)
        .put("mediaId", media.mediaId)
        .put("alternates", JSONArray(media.alternateUris.take(7)))
        .put("allowHttpRedirects", media.allowHttpRedirects)
        // Without this field, a working header-free VOD backup loses its
        // HTTP 404/410 failover permission between :spider and the player.
        .put("safeNotFoundFallback", media.safeNotFoundFallback &&
            media.alternateUris.isNotEmpty() && media.headers.isEmpty())
        .put("declaredMime", media.declaredMime ?: "")
        .put("danmaku", DanmakuProtocol.encodeSources(media.danmakuSources))
        .put("headers", JSONObject(media.headers))
        .put("subs", JSONArray().apply {
            media.subtitles.take(6).forEach { subtitle ->
                put(JSONObject().put("lang", subtitle.language)
                    .put("name", subtitle.title).put("url", subtitle.uri)
                    .put("format", subtitle.mimeType ?: FongMiSubtitleFormats.supportedMime(subtitle.uri, null))
                    .put("flag", FongMiSubtitleFormats.selectionFlag(subtitle.rawFlag)))
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
            val format = FongMiSubtitleFormats.supportedMime(trackUrl, track.optString("format"))
                ?: return@mapNotNull null
            val lang = track.optString("lang").trim().lowercase()
            if (!lang.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) return@mapNotNull null
            AuthorizedSubtitle(lang, track.optString("name").take(64), trackUrl,
                FongMiSubtitleFormats.selectionFlag(track.optInt("flag")), format)
        }
        val alternatives = response.optJSONArray("alternates") ?: JSONArray()
        val urls = (0 until minOf(alternatives.length(), 7)).mapNotNull { index ->
            TvBoxUrl.normalize(alternatives.optString(index), allowHttp)
        }.filter { it != url }.distinct()
        return PlaybackResult.Ready(PlaybackMedia(url, headers, subtitles, urls,
            response.optBoolean("allowHttpRedirects") && allowHttp,
            declaredMime = PlaybackFormatRetry.declaredMime(response.optString("declaredMime")),
            mediaId = response.optString("mediaId").takeIf { it.isNotBlank() && it.length <= 256 },
            // Re-validate in the receiving process. A malicious/forged IPC
            // flag cannot enable cross-origin fallback for authenticated
            // media or invent an absent provider-supplied backup.
            safeNotFoundFallback = response.optBoolean("safeNotFoundFallback", false) &&
                headers.isEmpty() && urls.isNotEmpty(),
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
    private val operations = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    @Volatile private var activeOperation = -1
    @Volatile private var retiring = false
    // Debug-only smoke fixture, never registered as a user-configurable source.
    private var proxySelfTest: NativeFongMiProxy? = null
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
        if (message.what == SpiderIpc.CANCEL_REQUEST) {
            val mustRetire=activeOperation == message.arg1 &&authCaller?.first==message.replyTo
            if (mustRetire) {
                retiring = true
                authorization.cancel()
                // A coroutine cannot interrupt arbitrary JNI. Retire ONLY this
                // manifest-verified :spider process; the UI process is untouched.
                watchdog.schedule({ android.os.Process.killProcess(android.os.Process.myPid()) },
                    250, TimeUnit.MILLISECONDS)
            }
            operations[message.arg1]?.cancel()
            runCatching { message.replyTo?.send(Message.obtain(null,SpiderIpc.CANCEL_REQUEST).apply{
                arg1=message.arg1;arg2=if(mustRetire)1 else 0
            }) }
            return@Handler true
        }
        if (message.what == SpiderIpc.AUTH_ACTION) {
            if (authCaller?.second == message.arg1) authorization.action(message.arg2, message.data.getString("button"))
            return@Handler true
        }
        val reply = message.replyTo
        val id = message.arg1
        val operation = message.what
        val payload = message.data.getString("payload").orEmpty()
        if (reply != null) {
            val operationBudget = message.data.getLong("operationBudgetMs", 150_000L).coerceIn(200,150_000)
            val task = scope.launch(start = CoroutineStart.LAZY) {
                val response = lock.withLock {
                    if (retiring) return@withLock SpiderIpc.error("Spider 会话正在回收")
                    activeOperation = id
                    authCaller = reply to id
                    // Cancellation cannot stop a JNI call that ignores interruption.
                    // This watchdog belongs solely to the manifest's :spider process.
                    val terminate = watchdog.schedule({
                        // If this fires, it is a HOST watchdog timeout, not proof
                        // of a native SIGSEGV. Persist only this fixed stage string.
                        SpiderStartupTrace.mark(applicationContext, SpiderStartupTrace.Stage.WATCHDOG)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, if (operationBudget == 150_000L) 160_000L else operationBudget + 300, TimeUnit.MILLISECONDS)
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
                                    }.orEmpty(),
                                    jsonParsers = SpiderIpc.readJsonParsers(
                                        request.optJSONArray("jsonParsers")),
                                    playbackParsers = SpiderIpc.readPlaybackParsers(
                                        request.optJSONArray("playbackParsers")))
                                val secret = request.getString("originSecret")
                                require(secret.matches(Regex("[a-f0-9-]{36}"))) { "Spider 授权会话无效" }
                                when (val result = worker.browse(site, warehouse, secret,
                                    loadHome = !request.optBoolean("searchOnly"))) {
                                    is SpiderResult.Ready -> JSONObject().put("ok", true)
                                        .put("films", JSONArray().apply {
                                            result.value.take(80).forEach { put(SpiderIpc.film(it)) }
                                        })
                                    is SpiderResult.Failure -> SpiderIpc.error(result.message)
                                }
                            }
                            SpiderIpc.SEARCH -> {
                                val site = SpiderIpc.readSite(request.getJSONObject("site"))
                                val keyword = request.optString("query").trim().take(120)
                                when (val result = worker.search(site, keyword)) {
                                    is SpiderResult.Ready -> JSONObject().put("ok", true)
                                        .put("films", JSONArray().apply {
                                            result.value.take(60).forEach { put(SpiderIpc.film(it)) }
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
                            SpiderIpc.RELEASE_PLAYBACK -> {
                                proxySelfTest?.close()
                                proxySelfTest = null
                                worker.releasePlayback()
                                JSONObject().put("ok", true)
                            }
                            SpiderIpc.PROXY_SELF_TEST -> {
                                check((applicationInfo.flags and
                                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                                    "Proxy smoke endpoint disabled in production"
                                }
                                val active = proxySelfTest ?: NativeFongMiProxy("ci-proxy", { _ ->
                                    arrayOf(200, "video/mp4",
                                        java.io.ByteArrayInputStream(byteArrayOf(8, 9, 10)))
                                }, null).also { proxySelfTest = it }
                                JSONObject().put("ok", true)
                                    .put("uri", active.siteUrl() + "&part=probe")
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
                        activeOperation = -1
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
            operations[id] = task
            task.invokeOnCompletion { operations.remove(id, task) }
            task.start()
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        // Chromium browser parser lives only in the :spider process.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { android.webkit.WebView.setDataDirectorySuffix("herrin-spider") }
        }
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
        proxySelfTest?.close()
        proxySelfTest = null
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
            scope.coroutineContext[Job]?.invokeOnCompletion { if(!retiring)watchdog.shutdownNow() }
            super.onDestroy()
        }
    }
}

/** Main-process facade: a plugin crash becomes a failure, not a TV UI crash.
 * HERRIN 2026-11-05: source-intent epochs extend the pinned FongMi
 * 305dff233cde512e2c83f71529b7c3ecfd61fefc closed-loader publication contract
 * across Binder suspension, explicit revocation and source switching.
 */
internal class TvBoxSpiderBridge(context: Context) {
    private val app = context.applicationContext
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private val searchRequests=mutableSetOf<Int>()
    private val waitingForBind = mutableListOf<CompletableDeferred<Messenger>>()
    private val nextId = SpiderIpc.requestIds
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private val requests = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cleanupJob: Job? = null
    private var retiredUntil = 0L
    private var retiringBinder:IBinder?=null
    private var retirement:CompletableDeferred<Boolean>?=null
    private var retirementId=-1
    private var retirementReaper:Job?=null
    internal fun pendingSearchRequests(): Int = pending.size
    @Volatile private var generation = 0
    @Volatile private var hydratedGeneration = -1
    private val sourceSession = SpiderSourceSession()
    private val configurationEpoch=java.util.concurrent.atomic.AtomicLong()
    internal fun configurationTicket()=configurationEpoch.get()
    private val lastBrowse: String? get() = sourceSession.descriptor()
    /** A read-only hint, not execution authorization; worker verifies identity. */
    fun approvedActiveSiteKey(): String? = lastBrowse?.let { payload ->
        runCatching { JSONObject(payload).getJSONObject("site").optString("key")
            .takeIf(String::isNotBlank) }.getOrNull()
    }

    // Only host-observed Binder/transport events authorize a single replay.
    // Provider messages, authentication denials and initialization errors do not.
    private enum class TransportFault { PROCESS_EXITED, DISCONNECTED, TIMEOUT }
    @Volatile private var lastTransportFault: TransportFault? = null

    private val responses = Messenger(Handler(Looper.getMainLooper()) { message ->
        if(message.what==SpiderIpc.CANCEL_REQUEST){
            if(message.arg1==retirementId)retirement?.complete(message.arg2==1)
            return@Handler true
        }
        if (message.what == SpiderIpc.AUTH_SHOW || message.what == SpiderIpc.AUTH_CLOSE) {
            // Android recycles Message immediately after this Handler returns.
            // UI actions happen later: capture values, never the pooled Message.
            val operationId = message.arg1
            val dialogId = message.arg2
            val server = message.replyTo
            val respond: (String?) -> Unit = { button ->
                runCatching { server?.send(Message.obtain(null, SpiderIpc.AUTH_ACTION).apply {
                    arg1 = operationId; arg2 = dialogId
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
            // HistoricalProcessExitReasons is a Binder query and recent()
            // reads disk. Playback enriches this diagnosis asynchronously;
            // neither belongs in the main-thread service-death callback.
            invalidate("Spider 子进程异常退出；可换一条直链或重新应用站点", TransportFault.PROCESS_EXITED)
        }

        override fun onBindingDied(name: ComponentName?) {
            if (epoch != generation || connection !== this) return
            invalidate("Spider 服务连接失效，请重试", TransportFault.DISCONNECTED)
        }

        override fun onNullBinding(name: ComponentName?) {
            if (epoch != generation || connection !== this) return
            invalidate("Spider 服务未提供 Binder")
        }
    }

    /** Main-thread only: release dead registrations and reject every outstanding caller. */
    private fun invalidate(reason: String, fault: TransportFault? = null) {
        lastTransportFault = fault
        if(pending.keys.any{it in searchRequests})sourceSession.revoke()
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
        val remaining = retiredUntil - android.os.SystemClock.uptimeMillis()
        if (remaining > 0) delay(remaining)
        val old=retiringBinder
        if(old!=null){
            val signal=retirement
            withTimeoutOrNull(600){while(signal?.isCompleted!=true &&old.isBinderAlive)delay(20)}
            val retired=if(signal?.isCompleted==true)signal.await() else null
            if(retired!=false){
                withTimeoutOrNull(800){while(old.isBinderAlive)delay(20)}
                check(!old.isBinderAlive){"Spider 子进程仍在回收，未复用旧执行会话"}
            }
            retiringBinder=null;retirement=null
            retirementReaper?.cancel();retirementReaper=null
        }
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
            invalidate("Spider 服务启动超时，请重试", TransportFault.TIMEOUT)
            throw timeout
        } finally {
            waitingForBind.remove(waiter)
            waiter.cancel()
        }
    }

    // Serialize source changes and media calls. A Binder death can be observed
    // during playerContent, so rehydrate the exact approved source before one
    // replay. Never replay a failed browse or an authentication/provider error.
    private suspend fun request(operation: Int, payload: JSONObject,
        invocation: Long = sourceSession.snapshot()): SpiderResult<JSONObject> {
        // Capture the intent before waiting for another loader operation.
        // An explicit close/source change must retire queued work as well.
        return requests.withLock {
            val authority=kotlinx.coroutines.currentCoroutineContext()[SpiderSearchAuthority]
            if(authority!=null &&authority.configuration!=configurationEpoch.get())
                return@withLock SpiderResult.Failure("来源配置已改变，已取消旧检索")
            if (operation == SpiderIpc.BROWSE) {
                val started = withContext(Dispatchers.Main.immediate) {
                    if(authority!=null &&authority.configuration!=configurationEpoch.get())return@withContext null
                    sourceSession.beginBrowse(invocation)?.let { ticket ->
                        hydratedGeneration = -1
                        ticket to generation
                    }
                } ?: return@withLock SpiderResult.Failure("Spider 会话已结束或变化，已取消应用")
                val result = sendRequest(operation, payload, started.first)
                if (result !is SpiderResult.Ready) return@withLock result
                return@withLock withContext(Dispatchers.Main.immediate) {
                    // Publish together with the binding generation on the same
                    // dispatcher as invalidate(), never after a stale success.
                    if (started.second != generation ||
                        !sourceSession.publish(started.first, payload.toString())) {
                        SpiderResult.Failure("Spider 会话已结束或变化，已取消应用")
                    } else {
                        hydratedGeneration = generation
                        result
                    }
                }
            }
            if (!sourceSession.isCurrent(invocation))
                return@withLock SpiderResult.Failure("Spider 会话已结束或变化，已取消操作")
            val remembered = lastBrowse
                ?: return@withLock SpiderResult.Failure("Spider 站点尚未就绪，请重新应用站点")
            for (attempt in 0..1) {
                if (!sourceSession.isCurrent(invocation))
                    return@withLock SpiderResult.Failure("Spider 会话已结束或变化，已取消恢复")
                val startedGeneration = generation
                if (hydratedGeneration != generation) {
                    when (val restored = sendRequest(SpiderIpc.BROWSE, JSONObject(remembered), invocation)) {
                        is SpiderResult.Failure -> return@withLock restored
                        is SpiderResult.Ready -> {
                            val current = withContext(Dispatchers.Main.immediate) {
                                if (startedGeneration != generation || !sourceSession.isCurrent(invocation)) false
                                else { hydratedGeneration = generation; true }
                            }
                            if (!current)
                                return@withLock SpiderResult.Failure("Spider 会话已变化，请重试")
                        }
                    }
                }
                val result = sendRequest(operation, payload, invocation)
                if (!sourceSession.isCurrent(invocation))
                    return@withLock SpiderResult.Failure("Spider 会话已结束或变化，已取消操作")
                if (startedGeneration == generation) return@withLock result
                if (result is SpiderResult.Ready)
                    return@withLock SpiderResult.Failure("Spider 会话已变化，请重试")
                if (attempt == 1 || lastTransportFault == null ||
                    kotlinx.coroutines.currentCoroutineContext()[SpiderRequestBudget] != null)
                    return@withLock result
                delay(400L) // Cancellation propagates; leaving playback never rebinds later.
                if (lastBrowse != remembered || closed || lastTransportFault == null)
                    return@withLock SpiderResult.Failure("Spider 会话已结束或变化，已取消恢复")
            }
            error("Unreachable bounded Spider recovery")
        }
    }

    private suspend fun sendRequest(operation: Int, payload: JSONObject,
        sourceEpoch: Long? = null): SpiderResult<JSONObject> = try {
        val response = withContext(Dispatchers.Main.immediate) {
            check(sourceEpoch == null || sourceSession.isCurrent(sourceEpoch)) { "Spider 会话已结束或变化" }
            val encoded = payload.toString()
            require(encoded.length <= SpiderIpc.MAX_REQUEST_CHARS) { "Spider 请求超过进程通信限制" }
            val server = service()
            check(sourceEpoch == null || sourceSession.isCurrent(sourceEpoch)) { "Spider 会话已结束或变化" }
            val id = nextId.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            val budget = kotlinx.coroutines.currentCoroutineContext()[SpiderRequestBudget]?.milliseconds
                ?.coerceIn(200,150_000) ?: 150_000L
            pending[id] = deferred
            if(kotlinx.coroutines.currentCoroutineContext()[SpiderRequestBudget]!=null)searchRequests.add(id)
            try {
                server.send(Message.obtain(null, operation).apply {
                    arg1 = id
                    replyTo = responses
                    data = Bundle().apply { putString("payload", encoded); putLong("operationBudgetMs",budget) }
                })
                withTimeout(if (budget == 150_000L) 165_000L else budget + 800) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                retire(server,id)
                retiredUntil = android.os.SystemClock.uptimeMillis() + 450
                sourceSession.revoke()
                invalidate("Spider 操作超时，请重新应用站点", TransportFault.TIMEOUT)
                throw timeout
            } catch (cancel: CancellationException) {
                retire(server,id)
                retiredUntil = android.os.SystemClock.uptimeMillis() + 450
                sourceSession.revoke()
                invalidate("Spider 旧请求已取消，正在回收执行会话")
                throw cancel
            } catch (error: android.os.RemoteException) {
                invalidate("Spider 服务已断开，请重试", TransportFault.DISCONNECTED)
                throw error
            } finally {
                pending.remove(id)
                searchRequests.remove(id)
                SpiderAuthorizationUi.clear(this@TvBoxSpiderBridge)
            }
        }
        require(response.length <= SpiderIpc.MAX_RESPONSE_CHARS) { "Spider 返回内容超过进程通信限制" }
        val result = withContext(Dispatchers.Default){JSONObject(response)}
        if (result.optBoolean("ok")) SpiderResult.Ready(result)
        else SpiderResult.Failure(result.optString("message").ifBlank { "Spider 返回错误" })
    } catch (timeout: TimeoutCancellationException) {
        kotlinx.coroutines.currentCoroutineContext().let { context -> context[Job]?.ensureActive() }
        SpiderResult.Failure("Spider 服务响应超时，请重新应用站点")
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        SpiderResult.Failure("Spider 服务错误：" + SpiderFailureDetails.message(error))
    }

    private fun retire(server:Messenger,id:Int){
        val old=server.binder
        val signal=CompletableDeferred<Boolean>()
        retiringBinder=old;retirementId=id;retirement=signal
        runCatching { server.send(Message.obtain(null,SpiderIpc.CANCEL_REQUEST).apply{arg1=id;replyTo=responses}) }
        retirementReaper?.cancel()
        retirementReaper=cleanupScope.launch {
            // Give cooperative cancellation and the child's own 250ms reaper priority.
            delay(650)
            // A local test/host Binder must never authorize process retirement.
            if(old is android.os.Binder ||retiringBinder!==old ||!old.isBinderAlive)return@launch
            if(signal.isCompleted &&!signal.await())return@launch
            val pid=withContext(Dispatchers.IO){
                runCatching {
                    val manager=app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    ownedSpiderPid(android.os.Process.myPid(),android.os.Process.myUid(),app.packageName,
                        manager.runningAppProcesses.orEmpty().mapNotNull{info->info.processName?.let{
                            OwnedSpiderProcess(info.pid,info.uid,it)}})
                }.getOrNull()
            }
            // Recheck the exact retired Binder after the IO query. A new binding
            // or completed cancellation must not be killed by an old watchdog.
            if(pid!=null &&retiringBinder===old &&old.isBinderAlive &&
                !(signal.isCompleted &&!signal.await())) {
                SpiderHostRetirement.requests.incrementAndGet()
                android.os.Process.killProcess(pid)
            }
        }
    }

    suspend fun browse(site: TvBoxSite, config: TvBoxConfig.Single, searchOnly: Boolean = false): SpiderResult<List<Film>> {
        val invocation = sourceSession.snapshot()
        withContext(Dispatchers.Main.immediate) { SpiderAuthorizationUi.clear(this@TvBoxSpiderBridge) }
        val payload = JSONObject().put("site", SpiderIpc.site(site))
            .put("searchOnly", searchOnly)
            .put("originSecret", sourceSession.filmSecret(invocation)
                ?: return SpiderResult.Failure("Spider 会话已变化"))
            .put("defaultJar", config.spiderJar ?: "")
            .put("vipFlags", JSONArray(config.vipFlags.take(32)))
            .put("jsonParsers", SpiderIpc.jsonParsers(config.jsonParsers))
            .put("playbackParsers", SpiderIpc.playbackParsers(config.playbackParsers))
        return when (val response = request(SpiderIpc.BROWSE, payload, invocation)) {
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

    /** Search ONLY the previously user-applied active Spider site. */
    suspend fun search(site: TvBoxSite, query: String): SpiderResult<List<Film>> {
        val invocation = sourceSession.snapshot()
        val keyword = query.trim().take(120)
        if (keyword.length < 2) return SpiderResult.Failure("搜索片名太短")
        val payload = JSONObject().put("site", SpiderIpc.site(site)).put("query", keyword)
        return when (val response = request(SpiderIpc.SEARCH, payload, invocation)) {
            is SpiderResult.Failure -> response
            is SpiderResult.Ready -> try {
                val array = response.value.getJSONArray("films")
                SpiderResult.Ready((0 until minOf(60, array.length())).map {
                    SpiderIpc.readFilm(array.getJSONObject(it))
                })
            } catch (_: Exception) {
                SpiderResult.Failure("Spider 搜索结果无效")
            }
        }
    }

    suspend fun details(film: Film): SpiderResult<Film> {
        val invocation = sourceSession.snapshot()
        if (!ownsFilm(film, invocation)) return SpiderResult.Failure("Spider 影片来源已失效，请重新查找片源")
        return when (val response = request(SpiderIpc.DETAILS,
            JSONObject().put("film", SpiderIpc.film(film)), invocation)) {
            is SpiderResult.Failure -> response
            is SpiderResult.Ready -> try {
                SpiderResult.Ready(SpiderIpc.readFilm(response.value.getJSONObject("film")))
            } catch (error: Exception) {
                SpiderResult.Failure("Spider 详情无效：" + SpiderFailureDetails.message(error))
            }
        }
    }

    suspend fun play(film: Film, episode: Int, line: String, allowHttp: Boolean): PlaybackResult {
        val invocation = sourceSession.snapshot()
        if (!ownsFilm(film, invocation))
            return PlaybackResult.Unavailable("Spider 影片或选集来源已失效，请重新查找片源")
        val payload = JSONObject().put("film", SpiderIpc.film(film))
            .put("episode", episode).put("line", line).put("allowHttp", allowHttp)
        return when (val response = request(SpiderIpc.PLAY, payload, invocation)) {
            is SpiderResult.Failure -> PlaybackResult.Unavailable(response.message)
            is SpiderResult.Ready -> try {
                val ready = SpiderIpc.readPlayback(response.value, allowHttp)
                val authorizedSiteKey = runCatching {
                    lastBrowse?.let { SpiderIpc.readSite(JSONObject(it).getJSONObject("site")).key }
                }.getOrNull().orEmpty()
                if (ready is PlaybackResult.Ready)
                    HerrinPlaybackDiagnosticJournal.record("SPIDER", "PLAYER_CONTENT_RETURNED")
                if (ready is PlaybackResult.Ready &&
                    NativeFongMiRoute.isNativeProxy(ready.media.uri)) {
                    val source = lastBrowse
                    val responseGeneration = generation
                    val health = probePlayback(ready.media.uri, authorizedSiteKey, invocation)
                        ?: return PlaybackResult.Unavailable("Spider 会话已结束或变化，已取消健康检查结果")
                    if (health != NativeFongMiRoute.Health.READY &&
                        !FongMiPlaybackRecovery.canRebuildProxy(health.name)) {
                        PlaybackResult.Unavailable("本机 Spider 代理健康检查失败（PROXY_${health.name}）")
                    } else if (health != NativeFongMiRoute.Health.READY) {
                        // The remote process can die between returning playerContent
                        // and the Media3 connection. Explicitly invalidate its
                        // bound session and retry at most once from the last
                        // previously approved source, never an arbitrary URL.
                        val rememberedSource = source
                        val retired = withContext(Dispatchers.Main.immediate) {
                            if (!sourceSession.isCurrent(invocation) || generation != responseGeneration ||
                                lastBrowse != rememberedSource) false
                            else { invalidate("Spider 播放代理连接失效"); true }
                        }
                        if (!retired) return PlaybackResult.Unavailable("Spider 会话已变化，已取消代理重建")
                        delay(400L)
                        if (rememberedSource == null || lastBrowse != rememberedSource)
                            return PlaybackResult.Unavailable("Spider 会话已结束或变化，已取消恢复")
                        when (val retried = request(SpiderIpc.PLAY, payload, invocation)) {
                            is SpiderResult.Failure -> PlaybackResult.Unavailable(
                                "本机 Spider 代理重连失败（PROXY_${health.name}）；" +
                                    retried.message)
                            is SpiderResult.Ready -> {
                                val fixed = SpiderIpc.readPlayback(retried.value, allowHttp)
                                if (fixed is PlaybackResult.Ready &&
                                    NativeFongMiRoute.isNativeProxy(fixed.media.uri)) {
                                    val retriedHealth = probePlayback(fixed.media.uri, authorizedSiteKey, invocation)
                                        ?: return PlaybackResult.Unavailable("Spider 会话已结束或变化，已取消恢复结果")
                                    if (retriedHealth != NativeFongMiRoute.Health.READY)
                                        PlaybackResult.Unavailable(
                                            "本机 Spider 播放代理无法启动（PROXY_${retriedHealth.name}），请重新应用影视站点")
                                    else fixed
                                } else fixed
                            }
                        }
                    } else ready
                } else ready
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                PlaybackResult.Unavailable("Spider 播放信息无效：" + SpiderFailureDetails.message(error))
            }
        }
    }

    private suspend fun probePlayback(uri: String, site: String, invocation: Long): NativeFongMiRoute.Health? {
        val transport = generation
        if (!sourceSession.isCurrent(invocation)) return null
        HerrinPlaybackDiagnosticJournal.record("PROXY", "HEALTH_STARTED")
        // Binder resumes on the caller's dispatcher. Compose calls from Main;
        // localhost HTTP is still network I/O and must never run there.
        val health = withContext(Dispatchers.IO) { NativeFongMiRoute.probe(uri, site) }
        if (!sourceSession.isCurrent(invocation) || generation != transport) return null
        HerrinPlaybackDiagnosticJournal.record("PROXY", "HEALTH_${health.name}")
        return health
    }

    fun isApprovedSourceFilm(film: Film): Boolean = ownsFilm(film, sourceSession.snapshot())

    private fun ownsFilm(film: Film, invocation: Long): Boolean = runCatching {
        val secret = sourceSession.filmSecret(invocation) ?: return false
        val source = JSONObject(lastBrowse ?: return false)
        val site = SpiderIpc.readSite(source.getJSONObject("site"))
        val jar = site.jar ?: source.optString("defaultJar")
        SpiderFilmAuthority(site.key, SpiderRuntimeCompat.sessionKey(jar, site), secret).accepts(film)
    }.getOrDefault(false)

    /**
     * Close before a subsequent play can reuse the bound service. Android may
     * rebind a service before onDestroy, so unbind alone is not a close barrier.
     * This job is owned by the bridge, not the outgoing media composition.
     */
    fun releasePlayback() {
        val selectedSource = lastBrowse
        val selectedGeneration = generation
        cleanupJob?.cancel()
        cleanupJob = cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.withLock {
                if (generation != selectedGeneration || lastBrowse != selectedSource) return@withLock
                if (remote == null && !bound) {
                    invalidate("Spider 播放会话已结束")
                    return@withLock
                }
                try {
                    withTimeout(6_000L) {
                        sendRequest(SpiderIpc.RELEASE_PLAYBACK, JSONObject())
                    }
                } catch (_: CancellationException) {
                    // Explicit close cancels this cleanup. The finally fence
                    // still releases registrations of this exact generation.
                } finally {
                    if (generation == selectedGeneration && lastBrowse == selectedSource)
                        invalidate("Spider 播放会话已结束")
                }
            }
        }
    }

    internal suspend fun awaitPlaybackReleaseForInstrumentation() { cleanupJob?.join() }

    internal suspend fun probeRemoteProxyForInstrumentation(): SpiderResult<String> =
        requests.withLock {
            when (val result = sendRequest(SpiderIpc.PROXY_SELF_TEST, JSONObject())) {
                is SpiderResult.Failure -> result
                is SpiderResult.Ready -> SpiderResult.Ready(result.value.optString("uri"))
            }
        }

    fun close() {
        configurationEpoch.incrementAndGet()
        // Revoke immediately, even when main-thread cleanup is still queued.
        sourceSession.revoke()
        val release = Runnable {
            if (closed) return@Runnable
            closed = true
            cleanupJob?.cancel()
            cleanupJob = null
            invalidate("Spider 会话已结束")
            closed = false // A later "应用影视源" starts a new bound session.
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run()
        else Handler(Looper.getMainLooper()).post(release)
    }
}
