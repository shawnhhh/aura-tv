package tv.herrin.app

import org.json.JSONObject
import java.io.IOException

/**
 * A single bounded TMDB API connectivity/authorization check after the user
 * saves their own v4 Read Access Token. Never log, return or display the token.
 * A 0/20 artwork result can mean network failure, not necessarily 20 bad titles.
 */
internal enum class TmdbProbeState(val text: String) {
    NOT_CONFIGURED("尚未配置 TMDB 令牌"),
    INVALID_FORMAT("TMDB 令牌格式不正确，请使用 API 读访问令牌（v4）"),
    CONNECTED("TMDB 配置与影片搜索接口均已连接，可以查询海报和影片资料"),
    SEARCH_UNAVAILABLE("TMDB 配置接口可访问，但影片搜索接口没有返回有效结果；请检查限流或网络代理"),
    RATE_LIMITED("TMDB 搜索请求受到限流（429）；请稍后重新刮削"),
    UNAUTHORIZED("TMDB 令牌未通过授权（401/403），请核对 v4 读访问令牌"),
    CONNECTION_FAILED("无法连接 TMDB，请检查电视网络、DNS 或访问限制"),
    SERVER_UNAVAILABLE("TMDB 服务暂时不可用，请稍后重试")
}

internal object TmdbConnectivityProbe {
    fun check(token: String, fetcher: ArtworkJsonFetcher = NetworkArtworkFetcher): TmdbProbeState {
        if (token.isBlank()) return TmdbProbeState.NOT_CONFIGURED
        if (!Regex("^[A-Za-z0-9._~-]{20,1000}$").matches(token.trim()))
            return TmdbProbeState.INVALID_FORMAT
        return try {
            // /3/configuration requires a valid Bearer token; responses contain
            // only public server configuration, no catalog or personal data.
            val reply = fetcher.load("https://api.themoviedb.org/3/configuration", token.trim())
                ?: return TmdbProbeState.SERVER_UNAVAILABLE
            if (!JSONObject(reply).has("images")) return TmdbProbeState.SERVER_UNAVAILABLE
            // A successful configuration request does not prove the search route works.
            val search = fetcher.load(
                "https://api.themoviedb.org/3/search/movie?query=Inception&language=zh-CN&include_adult=false&page=1",
                token.trim()) ?: return TmdbProbeState.SEARCH_UNAVAILABLE
            if (JSONObject(search).optJSONArray("results") != null) TmdbProbeState.CONNECTED
            else TmdbProbeState.SEARCH_UNAVAILABLE
        } catch (_: ArtworkAuthenticationFailure) {
            TmdbProbeState.UNAUTHORIZED
        } catch (_: ArtworkRateLimitFailure) {
            TmdbProbeState.RATE_LIMITED
        } catch (_: IOException) {
            TmdbProbeState.CONNECTION_FAILED
        } catch (_: Exception) {
            TmdbProbeState.SERVER_UNAVAILABLE
        }
    }
}
