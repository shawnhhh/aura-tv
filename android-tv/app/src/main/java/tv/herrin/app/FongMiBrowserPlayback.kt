package tv.herrin.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Playback-only browser resolver following the network-request interception
 * portion of FongMi ParseWebView, with no visible overlay and no changes to
 * HERRIN's Compose UI. Executed only after the user selects a configured
 * type=0 parser. No TLS bypass, JavaScript bridge or cross-host credentials. Requests are
 * confirmed using HTTP content type / bounded media bytes, not extensions.
 *
 * Reference: FongMi/TV fongmi@5e191aaced, GPL-3.0.
 */
internal object FongMiBrowserPlayback {
    private const val TIMEOUT_MS = 15_000L

    internal fun candidate(raw: String, allowHttp: Boolean): String? {
        val url = TvBoxUrl.normalize(raw, allowHttp) ?: return null
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return url.takeIf {
            listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv")
                .any(path::endsWith)
        }
    }

    /** Browser parsing is a playback decision only; no metadata is read. */
    fun choose(
        sourceUrl: String, flag: String, parsers: List<TvBoxPlaybackParser>,
        allowHttp: Boolean
    ): TvBoxPlaybackParser? {
        if (TvBoxUrl.normalize(sourceUrl, allowHttp) == null) return null
        val path = URI(sourceUrl).path.orEmpty().lowercase(Locale.ROOT)
        if (listOf(".m3u8", ".mp4", ".mpd", ".mkv", ".webm").any { path.endsWith(it) })
            return null
        return parsers.firstOrNull { it.type == 0 &&
            it.flags.any { f -> f.equals(flag, true) } }
            ?: if (path.endsWith(".html") || path.endsWith(".htm"))
                parsers.firstOrNull { it.type == 0 && it.flags.isEmpty() } else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context, parser: TvBoxPlaybackParser,
        sourceUrl: String, allowHttp: Boolean,
        detect: Boolean = true, nestedUrls: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet()),
        videoCheck: ((String) -> Boolean)? = null
    ): PlaybackResult {
        if (parser.type != 0) return PlaybackResult.Unavailable("该解析器不是网页类型")
        val prefix = if (parser.url.isEmpty()) "" else TvBoxUrl.normalize(parser.url, allowHttp)
            ?: return PlaybackResult.Unavailable("网页解析接口无效")
        val input = TvBoxUrl.normalize(sourceUrl, allowHttp)
            ?: return PlaybackResult.Unavailable("待解析地址无效")
        val target = TvBoxUrl.normalize(prefix + input, allowHttp)
            ?: return PlaybackResult.Unavailable("网页解析目标无效")
        val output = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                val main = Handler(Looper.getMainLooper())
                val children = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                suspendCancellableCoroutine<PlaybackResult> { cont ->
                    val view = try { WebView(context.applicationContext) }
                        catch (_: Exception) {
                            cont.resume(PlaybackResult.Unavailable("电视系统没有可用的网页解析运行环境"))
                            return@suspendCancellableCoroutine
                        }
                    val stopped = java.util.concurrent.atomic.AtomicBoolean()
                    val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
                    val probes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    fun cleanup() {
                        if (!stopped.compareAndSet(false, true)) return
                        calls.forEach { it.cancel() }
                        calls.clear()
                        children.cancel()
                        runCatching { view.stopLoading() }
                        runCatching { view.destroy() }
                    }
                    fun finish(result: PlaybackResult) {
                        if (!cont.isActive || stopped.get()) return
                        cleanup()
                        cont.resume(result)
                    }
                    cont.invokeOnCancellation { main.post { cleanup() } }
                    val ua = parser.headers.entries.firstOrNull {
                        it.key.equals("User-Agent", true)
                    }?.value?.takeIf { it.length in 1..2_048 }
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = if (allowHttp) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            else WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (ua != null) userAgentString = ua
                    }
                    val defaultAgent = view.settings.userAgentString
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView, request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (!stopped.get()) {
                                val found = TvBoxUrl.normalize(request.url.toString(), allowHttp)
                                val requestHeaders = request.requestHeaders + parser.headers.filterKeys {
                                    it.equals("User-Agent", true) || it.equals("Referer", true)
                                }
                                if (found != null && detect && Regex("player.*https?://").containsMatchIn(found) &&
                                    synchronized(nestedUrls) { nestedUrls.size < 5 && nestedUrls.add(found) }) {
                                    main.post {
                                        if (!stopped.get() && cont.isActive) children.launch {
                                            val child = resolve(context, parser.copy(url = "", headers = requestHeaders),
                                                found, allowHttp, false, nestedUrls, videoCheck)
                                            if (child is PlaybackResult.Ready) finish(child)
                                        }
                                    }
                                }
                                if (found != null && (detect || found != target) &&
                                    probes.size < 128 && probes.add(found) &&
                                    (videoCheck == null || runCatching { videoCheck(found) }.getOrDefault(false)) &&
                                    FongMiMediaProbe.probe(found, requestHeaders, allowHttp, calls)) {
                                    val mediaHeaders = buildMap<String, String> {
                                        val agent = ua ?: defaultAgent
                                        if (!agent.isNullOrBlank()) put("User-Agent", agent)
                                        val ref = request.requestHeaders.entries.firstOrNull {
                                            it.key.equals("Referer", true)
                                        }?.value
                                        if (!ref.isNullOrBlank() && ref.length < 2_048)
                                            put("Referer", ref)
                                    }
                                    main.post {
                                        // Chromium provides only cookies scoped to
                                        // this exact media URL, never site-wide
                                        // API credentials from a different host.
                                        val scopedCookie = runCatching {
                                            CookieManager.getInstance().getCookie(found)
                                        }.getOrNull().orEmpty()
                                        val finalHeaders = if (scopedCookie.isNotBlank() &&
                                            scopedCookie.length <= 2_048 &&
                                            '\r' !in scopedCookie && '\n' !in scopedCookie)
                                            mediaHeaders + ("Cookie" to scopedCookie)
                                        else mediaHeaders
                                        finish(PlaybackResult.Ready(PlaybackMedia(
                                            uri = found, headers = finalHeaders,
                                            allowHttpRedirects = allowHttp,
                                            declaredMime = PlaybackFormatRetry.initialMime(found)
                                        )))
                                    }
                                }
                            }
                            return null
                        }
                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            if (request.isForMainFrame)
                                finish(PlaybackResult.Unavailable(
                                    "网页解析入口无法加载（WebView 错误 ${error.errorCode}）"))
                        }
                        override fun onReceivedHttpError(
                            view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                        ) {
                            if (request.isForMainFrame)
                                finish(PlaybackResult.Unavailable(
                                    "网页解析入口返回 HTTP ${errorResponse.statusCode}"))
                        }
                        // FongMi's ParseWebView executes the parser's click
                        // script after HTML has loaded. Without this, many
                        // pages never request the video even though a
                        // WebView-based TVBox player can resolve it.
                        override fun onPageFinished(view: WebView, url: String) {
                            if (stopped.get() || !cont.isActive || parser.click.isBlank()) return
                            // Executed only in the disposable, isolated player
                            // resolver; no JS bridge or access to app UI/DB.
                            if (TvBoxUrl.normalize(url, allowHttp) == null) return
                            runCatching {
                                view.evaluateJavascript(parser.click.take(4_096), null)
                            }
                        }
                        override fun onRenderProcessGone(
                            view: WebView, detail: RenderProcessGoneDetail
                        ): Boolean {
                            finish(PlaybackResult.Unavailable("网页解析运行环境异常退出"))
                            return true
                        }
                    }
                    try {
                        val cookie = parser.headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
                        if (!cookie.isNullOrBlank()) CookieManager.getInstance().setCookie(target, cookie)
                        view.loadUrl(target, FongMiParsePlayback.safeHeaders(parser.headers, target, target))
                    } catch (_: Exception) {
                        finish(PlaybackResult.Unavailable("网页解析入口启动失败"))
                    }
                }
            }
        }
        return output ?: PlaybackResult.Unavailable("网页解析超时，请切换线路")
    }
}

/** Called only from the existing CMS player resolution branch; renders no UI. */
@Composable
internal fun CmsBrowserPlayback(
    sourceUrl: String, parser: TvBoxPlaybackParser, allowHttp: Boolean, retryEpoch: Int
): PlaybackResult? {
    val context = LocalContext.current
    var result by remember(sourceUrl, parser, allowHttp, retryEpoch) {
        mutableStateOf<PlaybackResult?>(null)
    }
    LaunchedEffect(sourceUrl, parser, allowHttp, retryEpoch) {
        result = FongMiBrowserPlayback.resolve(context, parser, sourceUrl, allowHttp)
    }
    return result
}
