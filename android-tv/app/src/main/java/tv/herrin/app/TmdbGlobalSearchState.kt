package tv.herrin.app

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Request keys belong to Compose effects; cancelled requests never publish into a newer query. */
@Stable
internal class TmdbGlobalSearchState {
    var requestKey = ""
    var films by mutableStateOf<List<Film>>(emptyList())
    var page by mutableIntStateOf(1)
    var lastLoadedPage by mutableIntStateOf(0)
    var totalPages by mutableIntStateOf(0)
    var retry by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var selectedSourceFailure by mutableStateOf<String?>(null)

    /** A user-selected, already running source needs no TMDB metadata credential. */
    suspend fun loadSelectedSpider(query: String, name: String,
                                   search: suspend () -> SpiderResult<List<Film>>) {
        val owner = requestKey
        films = emptyList(); totalPages = 0; lastLoadedPage = 0; selectedSourceFailure = null
        if (query.trim().length < 2) { status = "输入至少两个字，搜索当前影视点"; loading = false; return }
        loading = true
        status = "正在搜索 $name…"
        try {
            val reply = search()
            coroutineContext.ensureActive()
            if (requestKey != owner) return
            when (reply) {
                is SpiderResult.Ready -> {
                    films = reply.value
                    lastLoadedPage = 1; totalPages = 1
                    status = if (films.isEmpty()) "$name · 本次未返回匹配结果"
                        else "$name · ${films.size} 条搜索结果"
                }
                is SpiderResult.Failure -> {
                    val code = listOf("SOURCE_EMPTY_RESPONSE", "SOURCE_AUTH_REQUIRED", "SOURCE_ACCESS_DENIED",
                        "SOURCE_SESSION_EXPIRED", "SOURCE_RESPONSE_INVALID", "SOURCE_INTERFACE_ERROR")
                        .firstOrNull(reply.message::contains) ?: "SOURCE_SEARCH_FAILED"
                    selectedSourceFailure = code
                    status = when (code) {
                        "SOURCE_EMPTY_RESPONSE" -> "影视点返回空内容，尚不能判断资源或授权状态"
                        "SOURCE_AUTH_REQUIRED" -> "影视点要求来源授权，请按实际登录提示操作"
                        "SOURCE_ACCESS_DENIED" -> "影视点拒绝本次访问，请检查来源权限"
                        "SOURCE_SESSION_EXPIRED" -> "影视点会话已失效，请重新选择影视点"
                        else -> "影视点搜索失败，请检查来源或重试"
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (requestKey == owner) {
                selectedSourceFailure = "SOURCE_SEARCH_FAILED"
                status = "影视点搜索失败，请检查来源或重试"
            }
        }
        finally { if (requestKey == owner) loading = false }
    }

    suspend fun load(discovery: HerrinTmdbDiscovery, query: String, token: String) {
        selectedSourceFailure = null
        if (page == 1) { films = emptyList(); totalPages = 0; lastLoadedPage = 0 }
        if (query.isBlank()) { totalPages = 0; status = "输入片名，在线搜索 TMDB 全库"; return }
        loading = true
        status = "正在查询 TMDB…"
        try {
            val result = discovery.search(query, token, page)
            films = (if (page == 1) result.films else films + result.films).distinctBy(Film::id)
            totalPages = result.totalPages
            lastLoadedPage = result.page
            status = if (films.isEmpty()) "TMDB 实时查询无结果" else "TMDB 实时结果 · 第 $page 页"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: ArtworkAuthenticationFailure) { status = "TMDB 授权无效，请在设置中更新 Token" }
        catch (_: ArtworkRateLimitFailure) { status = "TMDB 请求限流，请稍后重试" }
        catch (_: Exception) { status = "TMDB 在线查询失败，请检查网络后重试" }
        finally {
            if (films.isNotEmpty() && lastLoadedPage != page) status += " · 保留之前已加载结果"
            loading = false
        }
    }
}
