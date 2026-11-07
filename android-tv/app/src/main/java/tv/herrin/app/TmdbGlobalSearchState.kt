package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException

/** Request keys belong to Compose effects; cancelled requests never publish into a newer query. */
@Stable
internal class TmdbGlobalSearchState {
    var films by mutableStateOf<List<Film>>(emptyList())
    var page by mutableIntStateOf(1)
    var totalPages by mutableIntStateOf(0)
    var retry by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    suspend fun load(discovery: HerrinTmdbDiscovery, query: String, token: String) {
        if (page == 1) films = emptyList()
        if (query.isBlank()) { totalPages = 0; status = "输入片名，在线搜索 TMDB 全库"; return }
        loading = true
        status = "正在查询 TMDB…"
        try {
            val result = discovery.search(query, token, page)
            films = (if (page == 1) result.films else films + result.films).distinctBy(Film::id)
            totalPages = result.totalPages
            status = if (films.isEmpty()) "TMDB 实时查询无结果" else "TMDB 实时结果 · 第 $page 页"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: ArtworkAuthenticationFailure) { status = "TMDB 授权无效，请在设置中更新 Token" }
        catch (_: ArtworkRateLimitFailure) { status = "TMDB 请求限流，请稍后重试" }
        catch (_: Exception) { status = "TMDB 在线查询失败，请检查网络后重试" }
        finally { loading = false }
    }
}
