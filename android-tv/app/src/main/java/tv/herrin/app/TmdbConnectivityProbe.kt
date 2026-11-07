package tv.herrin.app

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Read-only probe of the configuration AND real search endpoint. Diagnostics are
 * intentionally structured and never include exception messages, URLs or tokens.
 */
internal enum class TmdbProbeState(val text: String) {
    NOT_CONFIGURED("尚未配置 TMDB 令牌"),
    CHECKING("正在验证 TMDB 令牌和影片搜索接口…"),
    INVALID_FORMAT("TMDB 令牌格式不正确，请使用 API 读访问令牌（v4）"),
    CONNECTED("TMDB 配置与影片搜索接口均已连接，可以查询海报和影片资料"),
    SEARCH_UNAVAILABLE("TMDB 配置接口可访问，但影片搜索接口没有返回有效结果"),
    RATE_LIMITED("TMDB 请求受到限流（429），请稍后重新刮削"),
    UNAUTHORIZED("TMDB 令牌未通过授权（401/403），请核对 v4 读访问令牌"),
    CONNECTION_FAILED("无法连接 TMDB，请检查电视网络、DNS 或访问限制"),
    SERVER_UNAVAILABLE("TMDB 服务暂时不可用，请稍后重试")
}

internal data class TmdbProbeReport(
    val state: TmdbProbeState,
    val stage: String = "",
    val reason: String = ""
) {
    val text: String get() = if (stage.isBlank() || reason.isBlank()) state.text
        else "${state.text}（${stage}：${reason}）"
}

internal object TmdbConnectivityProbe {
    /** Legacy state-only API for older call sites and unit tests. */
    fun check(token: String, fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher): TmdbProbeState =
        checkDetailed(token, fetcher).state

    fun checkDetailed(
        token: String,
        fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher
    ): TmdbProbeReport {
        if (token.isBlank()) return TmdbProbeReport(TmdbProbeState.NOT_CONFIGURED)
        val trimmed = token.trim()
        if (!Regex("^[A-Za-z0-9._~-]{20,1000}$").matches(trimmed))
            return TmdbProbeReport(TmdbProbeState.INVALID_FORMAT)
        var stage = "配置接口"
        return try {
            val config = fetcher.load("${TmdbEndpoints.PRIMARY}/configuration", trimmed)
                ?: return TmdbProbeReport(TmdbProbeState.SERVER_UNAVAILABLE, stage, "没有有效 JSON 响应")
            if (JSONObject(config).optJSONObject("images") == null)
                return TmdbProbeReport(TmdbProbeState.SERVER_UNAVAILABLE, stage, "缺少 images 字段")
            stage = "搜索接口"
            val search = fetcher.load(
                "${TmdbEndpoints.PRIMARY}/search/movie?query=Inception&language=zh-CN&include_adult=false&page=1",
                trimmed
            ) ?: return TmdbProbeReport(TmdbProbeState.SEARCH_UNAVAILABLE, stage, "没有有效 JSON 响应")
            if (JSONObject(search).optJSONArray("results") == null)
                TmdbProbeReport(TmdbProbeState.SEARCH_UNAVAILABLE, stage, "响应缺少 results 数组")
            else TmdbProbeReport(TmdbProbeState.CONNECTED)
        } catch (auth: ArtworkAuthenticationFailure) {
            TmdbProbeReport(TmdbProbeState.UNAUTHORIZED, stage, "HTTP ${auth.status}")
        } catch (_: ArtworkRateLimitFailure) {
            TmdbProbeReport(TmdbProbeState.RATE_LIMITED, stage, "HTTP 429")
        } catch (http: ArtworkHttpStatusFailure) {
            TmdbProbeReport(TmdbProbeState.SERVER_UNAVAILABLE, stage, "HTTP ${http.status}")
        } catch (_: UnknownHostException) {
            TmdbProbeReport(TmdbProbeState.CONNECTION_FAILED, stage, "DNS 解析失败")
        } catch (_: SocketTimeoutException) {
            TmdbProbeReport(TmdbProbeState.CONNECTION_FAILED, stage, "连接或读取超时")
        } catch (_: SSLException) {
            TmdbProbeReport(TmdbProbeState.CONNECTION_FAILED, stage, "TLS/证书校验失败")
        } catch (_: ConnectException) {
            TmdbProbeReport(TmdbProbeState.CONNECTION_FAILED, stage, "TCP 连接失败")
        } catch (_: IOException) {
            TmdbProbeReport(TmdbProbeState.CONNECTION_FAILED, stage, "网络请求失败")
        } catch (_: Exception) {
            TmdbProbeReport(TmdbProbeState.SERVER_UNAVAILABLE, stage, "JSON 解析或响应格式异常")
        }
    }
}
