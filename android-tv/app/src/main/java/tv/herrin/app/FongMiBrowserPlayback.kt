package tv.herrin.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * type=0 parser. No TLS bypass, JavaScript bridge or cross-host credentials.
 *
 * Reference: FongMi/TV fongmi@5e191aaced, GPL-3.0.
 */
internal object FongMiBrowserPlayback {
    private const val TIMEOUT_MS = 18_000L

    internal fun candidate(raw: String, allowHttp: Boolean): String? {
        val url = TvBoxUrl.normalize(raw, allowHttp) ?: return null
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return url.takeIf {
            listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv")
                .any(path::endsWith)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context, parser: TvBoxPlaybackParser,
        sourceUrl: String, allowHttp: Boolean
    ): PlaybackResult {
        if (parser.type != 0) return PlaybackResult.Unavailable("该解析器不是网页类型")
        val prefix = TvBoxUrl.normalize(parser.url, allowHttp)
            ?: return PlaybackResult.Unavailable("网页解析接口无效")
        val input = TvBoxUrl.normalize(sourceUrl, allowHttp)
            ?: return PlaybackResult.Unavailable("待解析地址无效")
        val target = TvBoxUrl.normalize(prefix + input, allowHttp)
            ?: return PlaybackResult.Unavailable("网页解析目标无效")
        val output = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                val main = Handler(Looper.getMainLooper())
                suspendCancellableCoroutine<PlaybackResult> { cont ->
                    val view = try { WebView(context.applicationContext) }
                        catch (_: Exception) {
                            cont.resume(PlaybackResult.Unavailable("电视系统没有可用的网页解析运行环境"))
                            return@suspendCancellableCoroutine
                        }
                    var stopped = false
                    fun cleanup() {
                        if (stopped) return
                        stopped = true
                        runCatching { view.stopLoading() }
                        runCatching { view.destroy() }
                    }
                    fun finish(result: PlaybackResult) {
                        if (!cont.isActive || stopped) return
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
                            if (!stopped) {
                                val found = candidate(request.url.toString(), allowHttp)
                                if (found != null && found != target) {
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
                                        finish(PlaybackResult.Ready(PlaybackMedia(
                                            uri = found, headers = mediaHeaders,
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
                                finish(PlaybackResult.Unavailable("网页解析入口无法加载"))
                        }
                        override fun onReceivedHttpError(
                            view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                        ) {
                            if (request.isForMainFrame)
                                finish(PlaybackResult.Unavailable("网页解析入口返回 HTTP 错误"))
                        }
                        override fun onRenderProcessGone(
                            view: WebView, detail: RenderProcessGoneDetail
                        ): Boolean {
                            finish(PlaybackResult.Unavailable("网页解析运行环境异常退出"))
                            return true
                        }
                    }
                    try {
                        view.loadUrl(target, parser.headers.filterKeys { key ->
                            key.equals("Referer", true) || key.equals("User-Agent", true)
                        })
                    } catch (_: Exception) {
                        finish(PlaybackResult.Unavailable("网页解析入口启动失败"))
                    }
                }
            }
        }
        return output ?: PlaybackResult.Unavailable("网页解析超时，请切换线路")
    }
}
