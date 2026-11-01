/* SPDX-License-Identifier: GPL-3.0-only
 * Playback host for the directly ported FongMi ParseJob (fongmi@5e191aaced).
 * HERRIN 2026-11-01: process-local services, strict TLS/credential boundaries,
 * coroutine cancellation and PlaybackMedia conversion. No catalog or UI state.
 */
package tv.herrin.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.herrin.app.fongmi.parse.Parse
import tv.herrin.app.fongmi.parse.ParseCallback
import tv.herrin.app.fongmi.parse.ParseJob
import tv.herrin.app.fongmi.parse.Result
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object FongMiParsePlayback {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "fongmi-parse-timeout").apply { isDaemon = true }
    }
    private data class Origin(val scheme: String?, val host: String?, val port: Int)
    private fun origin(url: String): Origin? = runCatching {
        val uri = URI(url)
        Origin(uri.scheme?.lowercase(), uri.host?.lowercase(),
            if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80)
    }.getOrNull()
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val first = request.tag(Origin::class.java)
            val safe = if (first != null && first != origin(request.url.toString()))
                request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build()
            else request
            chain.proceed(safe)
        }.build()

    internal fun configuration(parser: TvBoxPlaybackParser): Parse = Parse.get(parser.type, parser.url).apply {
        name = parser.name
        click = parser.click
        if (parser.flags.isNotEmpty()) ext.setFlag(parser.flags)
        if (parser.headers.isNotEmpty()) ext.setHeader(parser.headers)
    }

    internal fun safeHeaders(headers: Map<String, String>, fromUrl: String, mediaUrl: String): Map<String, String> =
        headers.filter { (name, value) ->
            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) &&
                value.length <= 8_192 && '\r' !in value && '\n' !in value &&
                (!name.equals("Cookie", true) && !name.equals("Authorization", true) ||
                    origin(fromUrl) != null && origin(fromUrl) == origin(mediaUrl))
        }

    suspend fun resolve(
        context: Context, playerContent: String, flag: String,
        jsonParsers: List<TvBoxJsonParse>, allowHttp: Boolean,
        siteHeaders: Map<String, String> = emptyMap(),
        playbackParsers: List<TvBoxPlaybackParser> = emptyList(),
        loader: ClassLoader? = null, siteClick: String = "",
        vipFlags: List<String> = emptyList(),
        videoCheck: ((String) -> Boolean)? = null
    ): PlaybackResult {
        val root = runCatching { JSONObject(playerContent) }.getOrNull()
            ?: return PlaybackResult.Unavailable("PARSE_INVALID_JSON：Spider 播放数据不是有效 JSON")
        val result = runCatching { Result.fromObject(playerContent) }.getOrNull()
            ?: return PlaybackResult.Unavailable("PARSE_INVALID_RESULT：Spider 播放数据结构无效")
        result.flag = flag
        if (result.header.isEmpty()) result.header = HashMap(siteHeaders)
        val parsers = (playbackParsers + jsonParsers.filter { legacy ->
            playbackParsers.none { it.name == legacy.name }
        }.map { TvBoxPlaybackParser(it.name, 1, it.prefix, it.flags, it.headers) }).map(::configuration)
        // Result.isUseParse + PlayerManager's parse entry. An explicit playUrl
        // retains legacy Spider directives while all parsing runs through ParseJob.
        val useParse = result.jx == 1 || (result.playUrl.isEmpty() && vipFlags.contains(flag))
        if (!result.needParse() && !useParse && result.playUrl.isEmpty())
            return SpiderVodCodec.player(playerContent, allowHttp, siteHeaders)
        if (TvBoxUrl.normalize(result.url.v(), allowHttp) == null)
            return PlaybackResult.Unavailable("PARSE_INVALID_URL：待解析地址无效")
        return coroutineScope {
            val browserScope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Main.immediate)
            try {
                suspendCancellableCoroutine<PlaybackResult> { cont ->
                    val main = Handler(Looper.getMainLooper())
                    var selected: Parse? = null
                    val cause = java.util.concurrent.atomic.AtomicReference<String>()
                    val host = object : ParseJob.Host {
                        override fun getParse(): Parse = (parsers.firstOrNull() ?: Parse.get(0, "")).also { selected = it }
                        override fun getParse(name: String): Parse = (parsers.firstOrNull { it.name == name }
                            ?: Parse.get(0, "")).also { selected = it }
                        override fun getParses(): List<Parse> = parsers
                        override fun getParses(type: Int, flag: String): List<Parse> {
                            val items = parsers.filter { it.type == type }
                            return items.filter { it.ext.flag.contains(flag) }.ifEmpty { items }
                        }
                        override fun getSiteClick(key: String): String = siteClick
                        override fun onFailure(error: Throwable) {
                            val rootCause = generateSequence(error) { it.cause }.take(12).last()
                            val detail = when (rootCause) {
                                is java.util.concurrent.TimeoutException, is java.net.SocketTimeoutException -> "PARSE_TIMEOUT：解析超时"
                                is java.net.UnknownHostException -> "PARSE_DNS：解析接口域名解析失败"
                                is java.net.ConnectException -> "PARSE_CONNECT：解析接口连接失败"
                                is javax.net.ssl.SSLException -> "PARSE_TLS：解析接口 TLS 验证失败"
                                is ClassNotFoundException -> "PARSE_JAR_CLASS：JAR 缺少指定解析器"
                                is NoSuchMethodException -> "PARSE_JAR_ABI：JAR 解析器接口不兼容"
                                else -> Regex("Parser HTTP ([1-5][0-9]{2})").matchEntire(rootCause.message.orEmpty())
                                    ?.let { "PARSE_HTTP：解析接口 HTTP ${it.groupValues[1]}" }
                                    ?: "PARSE_FAILED：${rootCause.javaClass.simpleName}"
                            }
                            cause.set(detail)
                        }
                        override fun scheduler() = scheduler
                        override fun timeoutMillis() = 15_000L
                        override fun newCall(url: String, headers: Map<String, String>): Call {
                            val normalized = TvBoxUrl.normalize(url, allowHttp) ?: error("Invalid parser URL")
                            val request = Request.Builder().url(normalized)
                                .tag(Origin::class.java, origin(normalized))
                            safeHeaders(headers, normalized, normalized).forEach { (key, value) -> request.header(key, value) }
                            return client.newCall(request.build())
                        }
                        override fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): Any =
                            FongMiJarPlayback.invoke(loader, 2, key, "", "", jxs, null, url)
                        override fun jsonExtMix(flag: String, key: String, name: String,
                            jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): Any =
                            FongMiJarPlayback.invoke(loader, 3, key, name, flag, null, jxs, url)
                        override fun webSupported() = true
                        override fun post(task: Runnable) { main.post(task) }
                        override fun startWeb(key: String, from: String, headers: Map<String, String>,
                            url: String, click: String, callback: ParseCallback, detect: Boolean): AutoCloseable {
                            val task = browserScope.launch {
                                when (val output = FongMiBrowserPlayback.resolve(context,
                                    TvBoxPlaybackParser(from, 0, "", headers = headers, click = click), url, allowHttp, detect, videoCheck = videoCheck)) {
                                    is PlaybackResult.Ready -> callback.onParseSuccess(output.media.headers, output.media.uri, from)
                                    is PlaybackResult.Unavailable -> callback.onParseError()
                                }
                            }
                            return AutoCloseable { task.cancel() }
                        }
                    }
                    val job = ParseJob.create(host, object : ParseCallback {
                        override fun onParseSuccess(headers: Map<String, String>, url: String, from: String) {
                            if (!cont.isActive) return
                            val normalized = TvBoxUrl.normalize(url, allowHttp)
                            val parserUrl = parsers.firstOrNull { it.name == from }?.url
                                ?: selected?.url?.takeIf { it.startsWith("http") }
                                ?: result.playUrl.removePrefix("json:").takeIf { it.startsWith("http") }
                                ?: result.url.v()
                            cont.resume(if (normalized == null) PlaybackResult.Unavailable("PARSE_INVALID_RESULT_URL：解析器未提供有效媒体地址")
                            else PlaybackResult.Ready(PlaybackMedia(uri = normalized,
                                headers = safeHeaders(headers, parserUrl, normalized),
                                allowHttpRedirects = allowHttp,
                                declaredMime = PlaybackFormatRetry.declaredMime(root.optString("format")))))
                        }
                        override fun onParseError() {
                            if (cont.isActive) cont.resume(PlaybackResult.Unavailable(cause.get() ?: "PARSE_FAILED：解析器没有返回有效结果"))
                        }
                    })
                    cont.invokeOnCancellation { main.post { job.stop() } }
                    job.start(result, useParse)
                }
            } finally {
                browserScope.cancel()
            }
        }
    }
}
