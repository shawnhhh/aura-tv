package tv.herrin.app

import java.net.HttpURLConnection
import java.net.URL

/** Separate, credential-free CDN transport diagnosis. HTTP reachability is not image success. */
internal object TmdbImageConnectivity {
    fun check(): String {
        val connection = (URL("https://image.tmdb.org/").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 3000
            readTimeout = 3000
            instanceFollowRedirects = false
        }
        return try {
            "图片 CDN 独立探测：HTTP ${connection.responseCode}；仅表示该次 HTTP 响应，不代表 API 或所有图片可用"
        } catch (_: javax.net.ssl.SSLException) { "图片 CDN：TLS 校验失败" }
        catch (_: java.net.UnknownHostException) { "图片 CDN：DNS 失败" }
        catch (_: java.io.IOException) { "图片 CDN：连接失败或超时" }
        finally { connection.disconnect() }
    }
}
